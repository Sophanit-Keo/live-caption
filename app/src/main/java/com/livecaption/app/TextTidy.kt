package com.livecaption.app

/** Small fixes that make speech-recognition and machine-translation text read naturally. */
object TextTidy {

    private const val ZWSP = '​'

    private fun isKhmer(c: Char) = c in 'ក'..'៿' || c in '᧠'..'᧿'

    private fun isCjk(c: Char) =
        c in '぀'..'ヿ' || // Hiragana, Katakana
            c in '㐀'..'䶿' || c in '一'..'鿿' || c in '豈'..'﫿' || // Han
            c in '　'..'〿' || c in '＀'..'￯' // CJK punctuation, full-width forms

    /** Vosk's Chinese and Japanese models put a space between words; written text has none. */
    fun transcript(text: String, languageTag: String): String {
        val trimmed = text.trim()
        if (languageTag != "zh" && languageTag != "ja") return trimmed
        return removeSpacesBetween(trimmed, ::isCjk, "")
    }

    /**
     * Turns a finished speech-recognition result into a sentence: capital first letter and
     * end punctuation. Translation models handle full sentences better than bare word runs.
     */
    fun sentence(text: String, languageTag: String): String {
        val t = transcript(text, languageTag).replaceFirstChar { it.titlecase() }
        if (t.isEmpty() || t.last() in END_PUNCTUATION) return t
        return t + when {
            languageTag == "zh" || languageTag == "ja" -> "。"
            languageTag == "hi" -> "।"
            languageTag == "en" && looksLikeEnglishQuestion(t) -> "?"
            else -> "."
        }
    }

    private val END_PUNCTUATION = setOf('.', '?', '!', '。', '？', '！', '।', '។', '…')

    private val ENGLISH_QUESTION_START = setOf(
        "who", "what", "when", "where", "why", "how", "which", "whose", "is", "are", "am", "was", "were",
        "do", "does", "did", "can", "could", "will", "would", "should", "shall", "may", "have", "has",
    )

    private fun looksLikeEnglishQuestion(t: String) =
        t.substringBefore(' ').lowercase() in ENGLISH_QUESTION_START

    /**
     * The offline NLLB model writes Khmer with a space after every word, which reads wrongly:
     * Khmer separates words with nothing and phrases with spaces. When the text looks
     * word-split (short pieces), join the pieces with invisible zero-width spaces, which
     * still let lines wrap. Spaces before Khmer punctuation are always removed.
     */
    fun khmer(text: String): String {
        var t = text.trim().replace(Regex(" +([។៕?!])"), "$1")
        val pieces = t.split(' ').filter { p -> p.isNotEmpty() && p.all { isKhmer(it) || it == ZWSP } }
        if (pieces.size >= 3 && pieces.sumOf { it.length }.toDouble() / pieces.size < 6.5) {
            t = removeSpacesBetween(t, ::isKhmer, ZWSP.toString())
        }
        return t
    }

    /** Replaces each run of spaces that has a [match] character on both sides with [joiner]. */
    private fun removeSpacesBetween(text: String, match: (Char) -> Boolean, joiner: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ') {
                var j = i
                while (j < text.length && text[j] == ' ') j++
                val before = sb.lastOrNull()
                val after = text.getOrNull(j)
                if (before != null && after != null && match(before) && match(after)) sb.append(joiner)
                else sb.append(' ')
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
