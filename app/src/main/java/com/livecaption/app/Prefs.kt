package com.livecaption.app

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Khmer used to be two separate targets; it is now one language with a choice of engine.
        when (sp.getString("targetTag", "")) {
            "km-gemini" -> sp.edit { putString("targetTag", KHMER).putBoolean("useGemini", true) }
            "km-offline" -> sp.edit { putString("targetTag", KHMER).putBoolean("useGemini", false) }
        }
    }

    var sourceModel: String
        get() = sp.getString("sourceModel", null) ?: SpeechLanguage.ALL[0].modelName
        set(v) = sp.edit { putString("sourceModel", v) }

    /** Language to translate into (ML Kit tag or [KHMER]), or empty for captions without translation. */
    var targetTag: String
        get() = sp.getString("targetTag", "") ?: ""
        set(v) = sp.edit { putString("targetTag", v) }

    /** Translate online with Gemini (most accurate) instead of on the phone. */
    var useGemini: Boolean
        get() = sp.getBoolean("useGemini", false)
        set(v) = sp.edit { putBoolean("useGemini", v) }

    /** The user's Gemini API key, for online translation. */
    var cloudApiKey: String
        get() = sp.getString("cloudApiKey", "") ?: ""
        set(v) = sp.edit { putString("cloudApiKey", v) }

    var useMicrophone: Boolean
        get() = sp.getBoolean("useMicrophone", false)
        set(v) = sp.edit { putBoolean("useMicrophone", v) }

    var showOriginal: Boolean
        get() = sp.getBoolean("showOriginal", true)
        set(v) = sp.edit { putBoolean("showOriginal", v) }

    /** Read the translation aloud while headphones are connected. */
    var speakTranslation: Boolean
        get() = sp.getBoolean("speakTranslation", false)
        set(v) = sp.edit { putBoolean("speakTranslation", v) }

    /** Speaking speed of the voice; 1 is the engine's normal speed. */
    var voiceRate: Float
        get() = sp.getFloat("voiceRate", 1f)
        set(v) = sp.edit { putFloat("voiceRate", v) }

    /** Turn the other audio (the video being translated) down while the voice speaks. */
    var duckOtherAudio: Boolean
        get() = sp.getBoolean("duckOtherAudio", true)
        set(v) = sp.edit { putBoolean("duckOtherAudio", v) }

    val voiceSettings: VoiceSettings get() = VoiceSettings(voiceRate, duckOtherAudio)

    var textSizeSp: Float
        get() = sp.getFloat("textSizeSp", 18f)
        set(v) = sp.edit { putFloat("textSizeSp", v) }
}
