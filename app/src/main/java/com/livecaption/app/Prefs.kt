package com.livecaption.app

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // Version 0.1 stored the Khmer engine inside the language choice.
        when (sp.getString("targetTag", "")) {
            "km-gemini" -> sp.edit { putString("targetTag", "km"); putString("engine", TranslationEngine.GEMINI.name) }
            "km-offline" -> sp.edit { putString("targetTag", "km"); putString("engine", TranslationEngine.OFFLINE.name) }
        }
    }

    var sourceModel: String
        get() = sp.getString("sourceModel", null) ?: SpeechLanguage.ALL[0].modelName
        set(v) = sp.edit { putString("sourceModel", v) }

    /** Target language tag (see [TargetLanguage.tag]), or empty for captions without translation. */
    var targetTag: String
        get() = sp.getString("targetTag", "") ?: ""
        set(v) = sp.edit { putString("targetTag", v) }

    var engine: TranslationEngine
        get() = TranslationEngine.fromName(sp.getString("engine", null))
        set(v) = sp.edit { putString("engine", v.name) }

    /** The user's Gemini API key, for the Gemini and Gemini Live engines. */
    var geminiApiKey: String
        get() = sp.getString("cloudApiKey", "") ?: ""
        set(v) = sp.edit { putString("cloudApiKey", v) }

    var useMicrophone: Boolean
        get() = sp.getBoolean("useMicrophone", false)
        set(v) = sp.edit { putBoolean("useMicrophone", v) }

    var showOriginal: Boolean
        get() = sp.getBoolean("showOriginal", true)
        set(v) = sp.edit { putBoolean("showOriginal", v) }

    var textSizeSp: Float
        get() = sp.getFloat("textSizeSp", 18f)
        set(v) = sp.edit { putFloat("textSizeSp", v) }

    /** Speak translations aloud (Gemini Live's voice, or the phone's text-to-speech). */
    var voiceEnabled: Boolean
        get() = sp.getBoolean("voiceEnabled", true)
        set(v) = sp.edit { putBoolean("voiceEnabled", v) }

    /** Speech rate for the phone's text-to-speech voice (1 = normal). */
    var voiceRate: Float
        get() = sp.getFloat("voiceRate", 1f)
        set(v) = sp.edit { putFloat("voiceRate", v) }

    /** Lower other apps' audio while a translation is spoken. */
    var duckOthers: Boolean
        get() = sp.getBoolean("duckOthers", true)
        set(v) = sp.edit { putBoolean("duckOthers", v) }

    /** Speak through the phone's speaker when no headphones are connected. */
    var allowSpeaker: Boolean
        get() = sp.getBoolean("allowSpeaker", false)
        set(v) = sp.edit { putBoolean("allowSpeaker", v) }
}
