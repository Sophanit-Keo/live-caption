package com.livecaption.app

import org.json.JSONObject
import java.text.BreakIterator

/**
 * One row of rolling subtitle text: the last finished sentence plus the
 * sentence currently being spoken, trimmed from the front to stay short.
 */
class CaptionLine(private val maxChars: Int = 160) {
    private var committed = ""
    private var live = ""

    /** Update the in-progress sentence. */
    fun setLive(text: String) {
        live = text.trim()
    }

    /** A sentence is complete; it replaces the previous finished one. */
    fun commit(text: String) {
        committed = text.trim()
        live = ""
    }

    fun clear() {
        committed = ""
        live = ""
    }

    fun render(): String = displayTail(listOf(committed, live).filter { it.isNotEmpty() }.joinToString(" "), maxChars)
}

/**
 * Text that streams in as fragments (Gemini Live transcripts). Keeps the most recent part;
 * after a long silence the next fragment starts a fresh line instead of reviving old text.
 */
class RollingText(
    private val maxChars: Int = 160,
    private val resetAfterMs: Long = 8_000,
    private val clock: () -> Long,
) {
    private val text = StringBuilder()
    private var interim = ""
    private var lastUpdate = Long.MIN_VALUE / 2
    private var newSegment = false

    /** Adds a finished fragment, exactly as the service sent it (fragments carry their own spaces). */
    fun append(fragment: String) {
        if (fragment.isEmpty()) return
        touch()
        if (newSegment && text.isNotEmpty() && !text.last().isWhitespace() && !fragment.first().isWhitespace()) {
            text.append(' ')
        }
        newSegment = false
        text.append(fragment)
        interim = ""
        // Only the tail is ever shown; keep a margin for word-boundary trimming.
        if (text.length > maxChars * 3) text.delete(0, text.length - maxChars * 2)
    }

    /** Replaces the low-latency guess for words still being spoken. */
    fun setInterim(fragment: String) {
        touch()
        interim = fragment
    }

    /** The speaker paused: separate what comes next. */
    fun endSegment() {
        newSegment = true
    }

    fun render(): String {
        val joined = if (interim.isEmpty()) text.toString() else {
            val sep = if (text.isNotEmpty() && !text.last().isWhitespace() && !interim.first().isWhitespace()) " " else ""
            text.toString() + sep + interim
        }
        return displayTail(joined.trim(), maxChars)
    }

    private fun touch() {
        val now = clock()
        if (now - lastUpdate > resetAfterMs) {
            text.setLength(0)
            interim = ""
            newSegment = false
        }
        lastUpdate = now
    }
}

/** The last [maxChars] of [full], cut at a word (or character-cluster) boundary, with "…" if cut. */
fun displayTail(full: String, maxChars: Int): String {
    if (full.length <= maxChars) return full
    val cut = full.length - maxChars
    // Cut at a nearby word boundary when there is one, so we never show half a word.
    val space = full.indexOfAny(charArrayOf(' ', '​'), cut)
    if (space in cut until cut + MAX_WORD_SEARCH) return "…" + full.substring(space + 1)
    // Scripts written without spaces (Khmer, Chinese, …): never split a character cluster.
    val clusters = BreakIterator.getCharacterInstance().apply { setText(full) }
    val start = if (clusters.isBoundary(cut)) cut else clusters.following(cut)
    return "…" + full.substring(start)
}

private const val MAX_WORD_SEARCH = 24

/** Pulls [key] ("text" or "partial") out of a Vosk JSON result. */
fun parseVoskResult(json: String, key: String): String =
    try {
        JSONObject(json).optString(key).trim()
    } catch (e: Exception) {
        ""
    }

fun String.capitalizeFirst(): String = replaceFirstChar { it.titlecase() }
