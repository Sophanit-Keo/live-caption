package com.livecaption.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Calls [onChange] (main thread) whenever a setting changes; returns a handle for [unwatch]. */
    fun watch(onChange: (key: String?) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onChange(key) }
            .also { sp.registerOnSharedPreferenceChangeListener(it) }

    fun unwatch(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

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
        get() = sp.getFloat("textSizeSp", 20f)
        set(v) = sp.edit { putFloat("textSizeSp", v.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)) }

    var subtitleStyle: SubtitleStyle
        get() = SubtitleStyle.fromName(sp.getString("subtitleStyle", null))
        set(v) = sp.edit { putString("subtitleStyle", v.name) }

    /** Opacity of the caption box background, 0–1. */
    var boxOpacity: Float
        get() = sp.getFloat("boxOpacity", 0.7f)
        set(v) = sp.edit { putFloat("boxOpacity", v.coerceIn(0f, 1f)) }

    /** Lines of translated text shown at once. */
    var captionLines: Int
        get() = sp.getInt("captionLines", 2)
        set(v) = sp.edit { putInt("captionLines", v.coerceIn(1, 4)) }

    var centered: Boolean
        get() = sp.getBoolean("centered", true)
        set(v) = sp.edit { putBoolean("centered", v) }

    /** Distance of the caption box from the bottom of the screen, in pixels; -1 = default. */
    var overlayY: Int
        get() = sp.getInt("overlayY", -1)
        set(v) = sp.edit { putInt("overlayY", v) }

    fun appearance() = SubtitleAppearance(subtitleStyle, textSizeSp, boxOpacity, captionLines, centered, showOriginal)

    /** Gemini Live: show translated text when its voice says it, instead of as soon as it arrives. */
    var syncCaptions: Boolean
        get() = sp.getBoolean("syncCaptions", true)
        set(v) = sp.edit { putBoolean("syncCaptions", v) }

    /** Make Gemini Live's voice louder so it stands out over the original audio. */
    var voiceBoost: Boolean
        get() = sp.getBoolean("voiceBoost", true)
        set(v) = sp.edit { putBoolean("voiceBoost", v) }

    /** Optional description of what is being watched ("a football match"), to help translation. */
    var topic: String
        get() = sp.getString("topic", "") ?: ""
        set(v) = sp.edit { putString("topic", v) }

    /** Keep a transcript of each captions session on the phone (for reading, summaries, export). */
    var saveTranscripts: Boolean
        get() = sp.getBoolean("saveTranscripts", true)
        set(v) = sp.edit { putBoolean("saveTranscripts", v) }

    /** English name of the language summaries were last written in; empty = translation language. */
    var summaryLanguage: String
        get() = sp.getString("summaryLanguage", "") ?: ""
        set(v) = sp.edit { putString("summaryLanguage", v) }

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

    companion object {
        const val MIN_TEXT_SP = 12f
        const val MAX_TEXT_SP = 40f

        /** Settings that change how captions look; applied to running captions at once. */
        val APPEARANCE_KEYS = setOf("textSizeSp", "subtitleStyle", "boxOpacity", "captionLines", "centered", "showOriginal")
    }
}
