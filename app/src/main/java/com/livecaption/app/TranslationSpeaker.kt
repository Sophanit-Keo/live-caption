package com.livecaption.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.MissingResourceException
import kotlin.coroutines.resume

/** Finds headphones among the phone's audio outputs. */
object Headphones {
    private val TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** True for wired, USB and Bluetooth listening devices; false for the phone's own speakers. */
    fun isHeadphones(deviceType: Int): Boolean = deviceType in TYPES

    fun connected(audioManager: AudioManager): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphones(it.type) }

    /** Calls [onChange] now and whenever headphones are plugged in or removed; returns the handle to [unwatch]. */
    fun watch(audioManager: AudioManager, onChange: (connected: Boolean) -> Unit): AudioDeviceCallback {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onChange(connected(audioManager))
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onChange(connected(audioManager))
        }
        // Registering reports the devices that are already connected.
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unwatch(audioManager: AudioManager, callback: AudioDeviceCallback) =
        audioManager.unregisterAudioDeviceCallback(callback)
}

/** How the translation is spoken: [rate] is the base speed (1 = normal); [duckOthers] turns other audio down meanwhile. */
data class VoiceSettings(val rate: Float = 1f, val duckOthers: Boolean = true)

/**
 * Speed for the next sentence when [waiting] more are queued behind it: the voice speeds
 * up a little, never more than 20%, rather than falling further behind the speaker.
 */
fun speechRate(base: Float, waiting: Int): Float = base * (1f + 0.1f * waiting.coerceIn(0, 2))

/**
 * Sentences waiting to be spoken, one at a time. The sentence playing is never cut off;
 * when the voice falls behind, the oldest waiting sentences are dropped instead.
 */
class SpeechQueue(private val maxWaiting: Int = 2) {
    private val waiting = ArrayDeque<String>()

    var speaking = false
        private set

    /** Sentences queued behind the one playing. */
    val backlog: Int get() = waiting.size

    /** Queues [text]; returns it if it should start playing now because nothing else is. */
    fun add(text: String): String? {
        if (!speaking) {
            speaking = true
            return text
        }
        waiting.addLast(text)
        while (waiting.size > maxWaiting) waiting.removeFirst()
        return null
    }

    /** The sentence playing has ended; returns the next one to play, or null when there is none. */
    fun next(): String? {
        val text = waiting.removeFirstOrNull()
        speaking = text != null
        return text
    }

    fun clear() {
        waiting.clear()
        speaking = false
    }
}

/** What matters about a text-to-speech voice when choosing one. [language] is ISO 639-2, e.g. "khm". */
data class VoiceOption(
    val name: String,
    val language: String,
    val country: String,
    val quality: Int,
    val latency: Int,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

/**
 * The best installed voice for [language]: the engine's own country first ([country]),
 * then voices that work offline (no extra delay, and no silence when the connection
 * drops), then the highest quality and lowest latency. Ties keep the engine's choice ([current]).
 */
fun bestVoice(voices: List<VoiceOption>, language: String, country: String, current: String?): VoiceOption? =
    voices.filter { it.installed && it.language == language }
        .sortedWith(
            compareByDescending<VoiceOption> { it.country == country }
                .thenBy { it.needsNetwork }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
                .thenByDescending { it.name == current },
        )
        .firstOrNull()

/**
 * Reads finished translations aloud with the phone's text-to-speech voice.
 * It only speaks while headphones are connected, so the voice never plays over
 * the phone speaker (where the microphone would hear it and caption it again).
 */
class TranslationSpeaker(
    private val context: Context,
    private val locale: Locale,
    private val settings: VoiceSettings = VoiceSettings(),
) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    // Not one of the usages CaptionService captures, so the voice is never transcribed as device audio.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // Lowers the video or music being translated while the voice talks.
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var headphones = false
    private var hasFocus = false
    private var deviceCallback: AudioDeviceCallback? = null

    private val queue = SpeechQueue()

    /** The utterance the engine is playing; callbacks for any other are stale. */
    private var currentId: String? = null
    private var nextId = 0

    // Other audio stays lowered through the short gaps between sentences instead of pumping up and down.
    private val releaseFocus = Runnable { abandonFocus() }

    /** Name of the engine in use, e.g. "com.google.android.tts", once [prepare] succeeded. */
    var engineName: String? = null
        private set

    /**
     * Starts a text-to-speech engine with a voice for [locale]: the phone's default engine
     * if it has one, otherwise another installed engine that does. False if none has a voice.
     */
    suspend fun prepare(): Boolean {
        val engine = openEngineFor() ?: return false
        tts = engine
        val language = engine.setLanguage(locale)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) return false
        chooseVoice(engine)

        engine.setAudioAttributes(attributes)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
        })
        deviceCallback = Headphones.watch(audioManager) { connected ->
            headphones = connected
            if (!connected) silence()
        }
        ready = true
        return true
    }

    /** Queues [text] to be read aloud; does nothing while no headphones are connected. */
    fun speak(text: String) {
        if (!ready || !headphones || text.isBlank()) return
        queue.add(text)?.let(::play)
    }

    fun close() {
        ready = false
        deviceCallback?.let { Headphones.unwatch(audioManager, it) }
        deviceCallback = null
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        queue.clear()
        currentId = null
        handler.removeCallbacks(releaseFocus)
        abandonFocus()
    }

    private fun play(text: String) {
        val engine = tts ?: return
        handler.removeCallbacks(releaseFocus)
        if (settings.duckOthers && !hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        engine.setSpeechRate(speechRate(settings.rate, queue.backlog))
        val id = "caption-${nextId++}"
        currentId = id
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
    }

    /** Engine callbacks arrive on a background thread. */
    private fun finished(utteranceId: String) {
        handler.post {
            if (utteranceId != currentId) return@post
            currentId = null
            val next = queue.next()
            if (next != null) play(next) else handler.postDelayed(releaseFocus, FOCUS_HOLD_MS)
        }
    }

    private fun silence() {
        currentId = null
        queue.clear()
        tts?.stop()
        handler.removeCallbacks(releaseFocus)
        abandonFocus()
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private suspend fun openEngineFor(): TextToSpeech? {
        val default = open(null) ?: return null
        if (supportsLocale(default)) {
            engineName = default.defaultEngine
            return default
        }
        // Some phones' own engines (Samsung's, for one) have no voice for languages such as Khmer.
        val others = default.engines.map { it.name }
            .filter { it != default.defaultEngine }
            .sortedByDescending { it == GOOGLE_ENGINE }
        default.shutdown()
        for (name in others) {
            val engine = open(name) ?: continue
            if (supportsLocale(engine)) {
                engineName = name
                return engine
            }
            engine.shutdown()
        }
        return null
    }

    /** Starts engine [name] (null for the phone's default) and waits until it is ready. */
    private suspend fun open(name: String?): TextToSpeech? {
        var engine: TextToSpeech? = null
        val status = suspendCancellableCoroutine { continuation ->
            engine = TextToSpeech(context, { status ->
                handler.post { if (continuation.isActive) continuation.resume(status) }
            }, name)
        }
        if (status == TextToSpeech.SUCCESS) return engine
        engine?.shutdown()
        return null
    }

    private fun supportsLocale(engine: TextToSpeech): Boolean =
        engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE

    /** Switches to the best installed voice for the language; engines do not always default to it. */
    private fun chooseVoice(engine: TextToSpeech) {
        val voices = try {
            engine.voices.orEmpty()
        } catch (e: RuntimeException) {
            return // Some engines fail to list voices; keep the default one.
        }
        val current = engine.voice
        val options = voices.mapNotNull { it.toOption() }
        val language = iso3(locale) ?: return
        val best = bestVoice(options, language, current?.locale?.country ?: locale.country, current?.name) ?: return
        if (best.name != current?.name) voices.firstOrNull { it.name == best.name }?.let { engine.voice = it }
    }

    private fun Voice.toOption(): VoiceOption? {
        val language = iso3(locale) ?: return null
        val notInstalled = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
        return VoiceOption(name, language, locale.country, quality, latency, isNetworkConnectionRequired, !notInstalled)
    }

    private fun iso3(locale: Locale): String? =
        try {
            locale.isO3Language
        } catch (e: MissingResourceException) {
            null
        }

    companion object {
        private const val GOOGLE_ENGINE = "com.google.android.tts"
        private const val FOCUS_HOLD_MS = 2_000L

        /** The voice to use for a translation target chosen in settings. */
        fun localeFor(targetTag: String): Locale = Locale.forLanguageTag(targetTag)
    }
}
