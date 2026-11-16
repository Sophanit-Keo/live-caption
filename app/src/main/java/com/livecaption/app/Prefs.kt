package com.livecaption.app

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sourceModel: String
        get() = sp.getString("sourceModel", null) ?: SpeechLanguage.ALL[0].modelName
        set(v) = sp.edit { putString("sourceModel", v) }

    /** ML Kit language tag, or empty for captions without translation. */
    var targetTag: String
        get() = sp.getString("targetTag", "") ?: ""
        set(v) = sp.edit { putString("targetTag", v) }

    /** The user's Google Cloud Translation API key, for online-only languages. */
    var cloudApiKey: String
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
}
