package com.livecaption.app

/** Finds where sentences end, in many scripts. */
object Sentences {
    /** End a sentence wherever they appear (no space needed after them, as in Khmer and Chinese). */
    private const val STRONG_ENDS = "!?…。！？។៕؟।॥።"
    private const val CLOSERS = "\"'”’)]}»」』）"
    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e", "no", "fig", "approx")

    /**
     * End offsets of the complete sentences in [text]. A full stop ends a sentence only when
     * followed by a space (not in "3.5") and not after a known abbreviation ("Mr.") or an
     * initial ("J."). With [streaming], nothing at the very end counts yet: the next piece of
     * text may still continue it ("3." then "5", or "?" then "!").
     */
    fun boundaries(text: String, streaming: Boolean = false): List<Int> {
        val ends = ArrayList<Int>()
        var sentenceStart = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            var end = -1
            if (c in STRONG_ENDS || c == '.') {
                var j = i + 1
                while (j < text.length && (text[j] in STRONG_ENDS || text[j] == '.')) j++
                while (j < text.length && text[j] in CLOSERS) j++
                val atEnd = j >= text.length
                val strong = text.substring(i, j).any { it in STRONG_ENDS }
                end = when {
                    atEnd -> if (streaming) -1 else j
                    strong -> j
                    text[j].isWhitespace() && !isAbbreviation(text, sentenceStart, i) -> j
                    else -> -1
                }
                if (end < 0) {
                    i = j
                    continue
                }
            }
            if (end > 0) {
                if (text.substring(sentenceStart, end).isNotBlank()) ends += end
                sentenceStart = end
                i = end
            } else {
                i++
            }
        }
        return ends
    }

    /** [text] cut into trimmed sentences; the last one may be unfinished. */
    fun split(text: String, streaming: Boolean = false): List<String> {
        val result = ArrayList<String>()
        var start = 0
        for (end in boundaries(text, streaming)) {
            text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
        return result
    }

    private fun isAbbreviation(text: String, sentenceStart: Int, dot: Int): Boolean {
        var k = dot - 1
        while (k >= sentenceStart && (text[k].isLetter() || text[k] == '.')) k--
        val word = text.substring(k + 1, dot)
        return word.lowercase() in ABBREVIATIONS || (word.length == 1 && word[0].isUpperCase())
    }

    /**
     * How many of the newest items fit: the smallest start index such that items from there to
     * the end take at most [maxLines] lines ([linesFrom] measures them). The newest item is
     * always kept, even when it alone is longer.
     */
    fun fitNewest(count: Int, maxLines: Int, linesFrom: (Int) -> Int): Int {
        if (count <= 1) return 0
        var best = count - 1
        for (from in count - 2 downTo 0) {
            if (linesFrom(from) <= maxLines) best = from else break
        }
        return best
    }
}

/**
 * What the subtitles hold: finished sentences (oldest first) and the sentence being spoken,
 * whose part from [pendingStart] on may still change.
 */
data class SentenceSnapshot(val done: List<String>, val current: String, val pendingStart: Int = current.length) {
    val isEmpty: Boolean get() = done.isEmpty() && current.isEmpty()

    /** The sentence being spoken, or else the last finished one. */
    fun newest(): CaptionText =
        if (current.isNotEmpty()) CaptionText(current, pendingStart) else CaptionText(done.lastOrNull().orEmpty())

    /** All of it as one run of text, cut from the front to [maxChars] (continuous subtitles). */
    fun continuous(maxChars: Int = DEFAULT_MAX_CHARS): CaptionText {
        val full = (done + listOf(current).filter { it.isNotEmpty() }).joinToString(" ")
        val tail = displayTail(full, maxChars)
        val pendingLength = current.length - pendingStart
        return CaptionText(tail, if (pendingLength <= 0) tail.length else (tail.length - pendingLength).coerceAtLeast(0))
    }

    companion object {
        val EMPTY = SentenceSnapshot(emptyList(), "")
    }
}

/**
 * Caption text, kept as sentences so the subtitles can show them one by one. Text arrives as
 * whole finished chunks ([commit]: a recognised utterance or a translation), as streaming
 * fragments ([append]: Gemini Live transcripts), or as a replaceable guess for the words being
 * spoken ([setPending]). After [resetAfterMs] of silence the next text starts afresh.
 */
class SentenceBuffer(
    private val maxSentences: Int = 6,
    private val resetAfterMs: Long = 8_000,
    private val clock: () -> Long,
) {
    private val done = ArrayDeque<String>()
    private val open = StringBuilder()
    private var pending = ""
    private var lastUpdate = Long.MIN_VALUE / 2

    /** A finished piece of text: every sentence in it is complete. */
    fun commit(text: String) {
        touch()
        closeOpen()
        addDone(Sentences.split(text))
        pending = ""
    }

    /** Streaming final text, exactly as received (fragments carry their own spaces). */
    fun append(fragment: String) {
        if (fragment.isEmpty()) return
        touch()
        open.append(fragment)
        pending = ""
        val ends = Sentences.boundaries(open.toString(), streaming = true)
        if (ends.isNotEmpty()) {
            val text = open.toString()
            addDone(Sentences.split(text.substring(0, ends.last())))
            open.setLength(0)
            open.append(text.substring(ends.last()).trimStart())
        }
    }

    /** The words being spoken now; replaced by the next guess, cleared by [commit] or [append]. */
    fun setPending(text: String) {
        touch()
        pending = text.trim()
    }

    /** The speaker paused: what was being said is a finished sentence. */
    fun endSegment() = closeOpen()

    fun clear() {
        done.clear()
        open.setLength(0)
        pending = ""
    }

    fun snapshot(): SentenceSnapshot {
        val current = listOf(open.toString().trim(), pending).filter { it.isNotEmpty() }.joinToString(" ")
        return SentenceSnapshot(done.toList(), current, current.length - pending.length)
    }

    private fun closeOpen() {
        if (open.isNotBlank()) addDone(Sentences.split(open.toString()))
        open.setLength(0)
    }

    private fun addDone(sentences: List<String>) {
        for (s in sentences) if (s.isNotBlank()) done.addLast(s)
        while (done.size > maxSentences) done.removeFirst()
    }

    private fun touch() {
        val now = clock()
        if (now - lastUpdate > resetAfterMs) clear()
        lastUpdate = now
    }
}
