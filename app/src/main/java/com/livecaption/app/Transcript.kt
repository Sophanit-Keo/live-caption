package com.livecaption.app

/**
 * Tidies raw speech-recognizer output so it reads, and translates, more like written
 * text. Small Vosk models return lowercase words without punctuation, and separate
 * words with spaces even in Chinese and Japanese, which are written without them.
 */
object Transcript {

    /** Cleans up an utterance (finished or still being spoken) in [languageTag], e.g. "en". */
    fun tidy(text: String, languageTag: String): String {
        var result = text.trim().replace(WHITESPACE, " ")
        if (result.isEmpty()) return result
        if (languageTag == "zh" || languageTag == "ja") result = joinCjk(result)
        if (languageTag == "en") result = LOWERCASE_I.replace(result, "I")
        return result.replaceFirstChar { it.titlecase() }
    }

    /** A finished utterance: tidied and ended like a sentence, so translators treat it as one. */
    fun finish(text: String, languageTag: String): String {
        val result = tidy(text, languageTag)
        if (result.isEmpty() || result.last() in SENTENCE_ENDS) return result
        return result + when (languageTag) {
            "zh", "ja" -> "。"
            "hi" -> "।"
            else -> "."
        }
    }

    /** Drops spaces next to Chinese characters and Japanese kana, keeping those between Latin words. */
    private fun joinCjk(text: String): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val c = text[i]
            val dropped = c == ' ' && i > 0 && i < text.length - 1 &&
                (isCjk(text.codePointBefore(i)) || isCjk(text.codePointAt(i + 1)))
            if (!dropped) out.append(c)
        }
        return out.toString()
    }

    private fun isCjk(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ||
            codePoint in 0x3000..0x30FF || // CJK punctuation, hiragana, katakana
            codePoint in 0x31F0..0x31FF || // katakana extensions
            codePoint in 0xFF00..0xFFEF // full-width forms

    private val WHITESPACE = Regex("\\s+")
    private val LOWERCASE_I = Regex("\\bi\\b")
    private const val SENTENCE_ENDS = ".!?。！？।؟"
}
