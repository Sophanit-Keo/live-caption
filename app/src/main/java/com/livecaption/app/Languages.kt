package com.livecaption.app

import java.util.Locale

/** How captions are translated. */
enum class TranslationEngine {
    /** Vosk speech recognition + ML Kit (or NLLB for Khmer), entirely on the phone. */
    OFFLINE,

    /** Vosk speech recognition + Gemini text translation (online). */
    GEMINI,

    /** Gemini Live Translate: audio in, translated speech and both transcripts out (online). */
    LIVE,
    ;

    val needsApiKey get() = this != OFFLINE

    companion object {
        fun fromName(name: String?): TranslationEngine = entries.firstOrNull { it.name == name } ?: OFFLINE
    }
}

/**
 * A language captions can be translated into.
 *
 * @param tag our own identifier, stored in settings (an ML Kit tag where ML Kit has the language).
 * @param mlKit the ML Kit tag, if ML Kit translates into it offline.
 * @param live the Gemini Live Translate code, if Live Translate supports it.
 */
data class TargetLanguage(val tag: String, val mlKit: String?, val live: String?) {

    /** Offline support comes from ML Kit, or from the NLLB model for Khmer. */
    val offline: Boolean get() = mlKit != null || tag == KHMER

    fun supports(engine: TranslationEngine): Boolean = when (engine) {
        TranslationEngine.OFFLINE -> offline
        TranslationEngine.GEMINI -> true
        TranslationEngine.LIVE -> live != null
    }

    /** Locale for showing the language's name. */
    val locale: Locale get() = Locale.forLanguageTag(live ?: tag)

    /** Locale to ask the phone's text-to-speech engine for (engines expect a region for some languages). */
    val voiceLocale: Locale get() = Locale.forLanguageTag(VOICE_TAGS[live ?: tag] ?: live ?: tag)

    /** English name, used in prompts sent to Gemini. */
    val englishName: String get() = locale.getDisplayName(Locale.ENGLISH)

    fun displayName(inLocale: Locale = Locale.getDefault()): String =
        locale.getDisplayName(inLocale).replaceFirstChar { it.titlecase(inLocale) }

    companion object {
        const val KHMER = "km"

        /** Every language Gemini 3.5 Live Translate can speak (from its documentation). */
        val LIVE_CODES = setOf(
            "af", "ak", "sq", "am", "ar", "hy", "az", "eu", "be", "bn", "bg", "my", "ca", "zh-Hans", "zh-Hant",
            "hr", "cs", "da", "nl", "en", "et", "fil", "fi", "fr", "gl", "ka", "de", "el", "gu", "ha", "he", "hi",
            "hu", "is", "id", "it", "ja", "jv", "kn", "kk", "km", "rw", "ko", "lo", "lv", "lt", "mk", "ms", "ml",
            "mr", "mn", "ne", "no", "fa", "pl", "pt-BR", "pt-PT", "pa", "ro", "ru", "sr", "sd", "si", "sk", "sl",
            "es", "su", "sw", "sv", "ta", "te", "th", "tr", "uk", "ur", "uz", "vi", "zu",
        )

        private val VOICE_TAGS = mapOf("km" to "km-KH", "zh-Hans" to "zh-CN", "zh-Hant" to "zh-TW", "fil" to "fil-PH")

        /** ML Kit tags whose Live Translate code is spelled differently. */
        private val ML_KIT_TO_LIVE = mapOf("zh" to "zh-Hans", "pt" to "pt-BR", "tl" to "fil")

        fun liveCodeFor(mlKitTag: String): String? =
            (ML_KIT_TO_LIVE[mlKitTag] ?: mlKitTag).takeIf { it in LIVE_CODES }

        /** All targets: ML Kit's languages, Khmer, then languages only Gemini Live adds. */
        fun catalog(mlKitTags: Collection<String>): List<TargetLanguage> {
            val list = mlKitTags.map { TargetLanguage(it, it, liveCodeFor(it)) }.toMutableList()
            if (list.none { it.tag == KHMER }) list += TargetLanguage(KHMER, null, KHMER)
            val covered = list.mapNotNull { it.live }.toSet()
            for (code in LIVE_CODES - covered) list += TargetLanguage(code, null, code)
            return list
        }
    }
}
