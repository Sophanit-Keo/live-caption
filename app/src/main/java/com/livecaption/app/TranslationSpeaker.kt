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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
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

/**
 * How fast to speak with [backlog] sentences still waiting: the voice speeds up
 * a little rather than falling further behind the speaker.
 */
fun speechRate(backlog: Int): Float = (1f + 0.15f * backlog.coerceAtLeast(0)).coerceAtMost(1.45f)

/**
 * Reads finished translations aloud with the phone's text-to-speech voice.
 * It only speaks while headphones are connected, so the voice never plays over
 * the phone speaker (where the microphone would hear it and caption it again).
 */
class TranslationSpeaker(private val context: Context, private val locale: Locale) {

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

    /** Sentences handed to the engine and not finished yet. */
    private val outstanding = mutableSetOf<String>()
    private var nextId = 0

    /** Starts the text-to-speech engine; false if the phone has no voice for [locale]. */
    suspend fun prepare(): Boolean {
        val status = suspendCancellableCoroutine { continuation ->
            tts = TextToSpeech(context) { status ->
                handler.post { if (continuation.isActive) continuation.resume(status) }
            }
        }
        val engine = tts ?: return false
        if (status != TextToSpeech.SUCCESS) return false
        val language = engine.setLanguage(locale)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) return false

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
        val engine = tts ?: return
        if (!ready || !headphones || text.isBlank()) return

        // Too far behind the speaker: drop what is waiting and say the newest sentence.
        val mode = if (outstanding.size >= MAX_BACKLOG) {
            outstanding.clear()
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        if (!hasFocus) {
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        engine.setSpeechRate(speechRate(outstanding.size))
        val id = "caption-${nextId++}"
        outstanding += id
        if (engine.speak(text, mode, null, id) != TextToSpeech.SUCCESS) finished(id)
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
        outstanding.clear()
        releaseFocus()
    }

    private fun silence() {
        tts?.stop()
        outstanding.clear()
        releaseFocus()
    }

    /** Engine callbacks arrive on a background thread. */
    private fun finished(utteranceId: String) {
        handler.post {
            if (outstanding.remove(utteranceId) && outstanding.isEmpty()) releaseFocus()
        }
    }

    private fun releaseFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    companion object {
        private const val MAX_BACKLOG = 2

        /** The voice to use for a translation target chosen in settings. */
        fun localeFor(targetTag: String): Locale =
            Locale.forLanguageTag(if (KhmerTargets.isKhmer(targetTag)) "km" else targetTag)
    }
}
