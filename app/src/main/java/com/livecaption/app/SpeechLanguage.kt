package com.livecaption.app

/**
 * A spoken language we can transcribe. [modelName] is the Vosk model archive name;
 * [translateTag] is the matching ML Kit translation language tag.
 */
data class SpeechLanguage(
    val label: String,
    val modelName: String,
    val translateTag: String,
    val sizeMb: Int,
) {
    val url: String get() = "https://alphacephei.com/vosk/models/$modelName.zip"

    override fun toString(): String = "$label  ·  $sizeMb MB"

    companion object {
        val ALL = listOf(
            SpeechLanguage("English (US)", "vosk-model-small-en-us-0.15", "en", 40),
            SpeechLanguage("English (India)", "vosk-model-small-en-in-0.4", "en", 36),
            SpeechLanguage("Arabic", "vosk-model-small-ar-0.3", "ar", 100),
            SpeechLanguage("Catalan", "vosk-model-small-ca-0.4", "ca", 42),
            SpeechLanguage("Chinese", "vosk-model-small-cn-0.22", "zh", 42),
            SpeechLanguage("Czech", "vosk-model-small-cs-0.4-rhasspy", "cs", 44),
            SpeechLanguage("Dutch", "vosk-model-small-nl-0.22", "nl", 39),
            SpeechLanguage("Esperanto", "vosk-model-small-eo-0.42", "eo", 42),
            SpeechLanguage("French", "vosk-model-small-fr-0.22", "fr", 41),
            SpeechLanguage("Georgian", "vosk-model-small-ka-0.42", "ka", 44),
            SpeechLanguage("German", "vosk-model-small-de-0.15", "de", 45),
            SpeechLanguage("Gujarati", "vosk-model-small-gu-0.42", "gu", 103),
            SpeechLanguage("Hindi", "vosk-model-small-hi-0.22", "hi", 43),
            SpeechLanguage("Italian", "vosk-model-small-it-0.22", "it", 48),
            SpeechLanguage("Japanese", "vosk-model-small-ja-0.22", "ja", 48),
            SpeechLanguage("Korean", "vosk-model-small-ko-0.22", "ko", 83),
            SpeechLanguage("Persian", "vosk-model-small-fa-0.42", "fa", 51),
            SpeechLanguage("Polish", "vosk-model-small-pl-0.22", "pl", 51),
            SpeechLanguage("Portuguese", "vosk-model-small-pt-0.3", "pt", 31),
            SpeechLanguage("Russian", "vosk-model-small-ru-0.22", "ru", 45),
            SpeechLanguage("Spanish", "vosk-model-small-es-0.42", "es", 38),
            SpeechLanguage("Telugu", "vosk-model-small-te-0.42", "te", 58),
            SpeechLanguage("Turkish", "vosk-model-small-tr-0.3", "tr", 36),
            SpeechLanguage("Ukrainian", "vosk-model-small-uk-v3-small", "uk", 138),
            SpeechLanguage("Vietnamese", "vosk-model-small-vn-0.4", "vi", 33),
        )

        fun byModelName(name: String?): SpeechLanguage = ALL.firstOrNull { it.modelName == name } ?: ALL[0]
    }
}
