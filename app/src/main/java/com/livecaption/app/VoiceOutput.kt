package com.livecaption.app

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** Audio settings shared by everything that speaks a translation. */
object VoiceAudio {
    /**
     * "Assistant" audio follows media routing (headphones when connected) but is never part
     * of other apps' playback capture, and capture is refused explicitly as well — so our own
     * voice can't be fed back into the captions.
     */
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()

    /** Apps of the installed text-to-speech engines; their audio is excluded from capture too. */
    fun ttsEngineUids(context: Context): List<Int> =
        try {
            context.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.applicationInfo?.uid }
                .distinct()
        } catch (e: Exception) {
            emptyList()
        }
}

/** Lowers other apps' audio (ducking) while a translation is being spoken. */
class VoiceFocus(context: Context, private val duckOthers: Boolean, private val onLost: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private val release = Runnable { abandon() }

    /** Call on the main thread whenever speaking starts or stops. */
    fun setSpeaking(speaking: Boolean) {
        handler.removeCallbacks(release)
        if (speaking) acquire() else handler.postDelayed(release, HANG_MS) // Avoid volume pumping between sentences.
    }

    private fun acquire() {
        if (!duckOthers || request != null) return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(VoiceAudio.attributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    abandon()
                    onLost() // e.g. a phone call: stop talking over it.
                }
            }, handler)
            .build()
        if (audio.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request = r
    }

    fun abandon() {
        handler.removeCallbacks(release)
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
    }

    private companion object {
        const val HANG_MS = 1_200L
    }
}

/** Tracks whether headphones (wired, USB, Bluetooth or hearing aids) are connected. */
class HeadphoneMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _connected = MutableStateFlow(check())
    val connected: StateFlow<Boolean> = _connected

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
    }

    fun start() = audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))

    fun stop() = audio.unregisterAudioDeviceCallback(callback)

    private fun update() {
        _connected.value = check()
    }

    private fun check(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphone(it.type) }

    companion object {
        @Suppress("InlinedApi") // TYPE_BLE_HEADSET is simply never reported before Android 12.
        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        fun isHeadphone(type: Int) = type in HEADPHONE_TYPES
    }
}

/** Result of looking for a text-to-speech voice for a language. */
sealed interface VoiceLookup {
    /** [tts] is initialised with the language set; the caller owns it. */
    class Ready(val tts: TextToSpeech, val enginePackage: String, val engineLabel: String) : VoiceLookup

    /** An engine has the language but its voice data is not downloaded yet. */
    data class NeedsDownload(val enginePackage: String, val engineLabel: String) : VoiceLookup

    data object Unsupported : VoiceLookup
}

/** Speaks translated sentences with the phone's text-to-speech engine. */
class TtsVoice(
    private val tts: TextToSpeech,
    private val baseRate: Float,
    private val onSpeaking: (Boolean) -> Unit,
) {
    private val queue = SpeechQueue()
    private val main = Handler(Looper.getMainLooper())
    private var speaking = false
    private var nextId = 0

    init {
        tts.setAudioAttributes(VoiceAudio.attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post(::speakNext)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post(::speakNext)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post(::speakNext)
            }
        })
    }

    /** Main thread only. */
    fun speak(text: String) {
        queue.add(text)
        if (!speaking) speakNext()
    }

    private fun speakNext() {
        val text = queue.next()
        if (text == null) {
            setSpeaking(false)
            return
        }
        setSpeaking(true)
        tts.setSpeechRate(baseRate * queue.rateFactor())
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "caption-${nextId++}")
    }

    /** Stops talking and forgets queued sentences. */
    fun stop() {
        queue.clear()
        tts.stop()
        setSpeaking(false)
    }

    fun close() {
        stop()
        tts.shutdown()
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        onSpeaking(value)
    }

    companion object {
        /** Finds an engine that speaks [locale]: the default engine first, then any other installed one. */
        suspend fun find(context: Context, locale: Locale): VoiceLookup {
            val first = open(context, null) ?: return VoiceLookup.Unsupported
            val engines = first.engines.orEmpty()
            val default = first.defaultEngine.orEmpty()
            fun label(pkg: String) = engines.firstOrNull { it.name == pkg }?.label ?: pkg
            var needsDownload: VoiceLookup.NeedsDownload? = null

            fun check(tts: TextToSpeech, pkg: String): VoiceLookup? = when (tts.setLanguage(locale)) {
                TextToSpeech.LANG_MISSING_DATA -> {
                    if (needsDownload == null) needsDownload = VoiceLookup.NeedsDownload(pkg, label(pkg))
                    null
                }
                TextToSpeech.LANG_NOT_SUPPORTED -> null
                else -> VoiceLookup.Ready(tts, pkg, label(pkg))
            }

            check(first, default)?.let { return it }
            first.shutdown()
            for (engine in engines.map { it.name }.filter { it != default }) {
                val tts = open(context, engine) ?: continue
                check(tts, engine)?.let { return it }
                tts.shutdown()
            }
            return needsDownload ?: VoiceLookup.Unsupported
        }

        private suspend fun open(context: Context, engine: String?): TextToSpeech? =
            suspendCancellableCoroutine { cont ->
                var created: TextToSpeech? = null
                var status: Int? = null
                val listener = TextToSpeech.OnInitListener { result ->
                    status = result
                    val tts = created ?: return@OnInitListener // Reported during construction: handled below.
                    if (result == TextToSpeech.SUCCESS) cont.resume(tts) else {
                        tts.shutdown()
                        cont.resume(null)
                    }
                }
                val tts = TextToSpeech(context.applicationContext, listener, engine)
                created = tts
                status?.let { result ->
                    if (result == TextToSpeech.SUCCESS) cont.resume(tts) else {
                        tts.shutdown()
                        cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { tts.shutdown() }
            }
    }
}

/**
 * Plays the stream of translated speech from Gemini Live (16-bit mono PCM) as it arrives.
 * When the speaker talks faster than the translation can be played, playback speeds up a
 * little, and a very long backlog is skipped so the voice stays close to the video.
 */
class PcmPlayer(val sampleRate: Int, private val onSpeaking: (Boolean) -> Unit) {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(VoiceAudio.attributes)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                sampleRate, // half a second
            ),
        )
        .build()

    private val queue = LinkedBlockingDeque<ByteArray>()
    private val queuedBytes = AtomicLong()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    @Volatile private var flushRequested = false
    private var framesWritten = 0L
    private var speaking = false
    private var speed = 1f

    private val writer = thread(name = "translation-voice") {
        track.play()
        while (running) {
            if (flushRequested) doFlush()
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
            if (chunk != null) {
                queuedBytes.addAndGet(-chunk.size.toLong())
                setSpeaking(true)
                var offset = 0
                while (offset < chunk.size && running && !flushRequested) {
                    val n = track.write(chunk, offset, chunk.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                framesWritten += offset / 2
                adjustSpeed()
            } else if (speaking && unplayedFrames() == 0L) {
                setSpeaking(false)
            }
        }
    }

    /** Any thread. */
    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty() || !running) return
        queue.add(pcm)
        queuedBytes.addAndGet(pcm.size.toLong())
    }

    /** Drops everything not yet heard (the model was interrupted, or voice was switched off). */
    fun flush() {
        queue.clear()
        queuedBytes.set(0)
        flushRequested = true
    }

    fun release() {
        running = false
        writer.join(1_000)
        track.release()
    }

    /** Milliseconds of translated speech waiting to be heard. */
    private fun backlogMs(): Long = (queuedBytes.get().coerceAtLeast(0) / 2 + unplayedFrames()) * 1000 / sampleRate

    private fun unplayedFrames(): Long {
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (framesWritten - head).coerceAtLeast(0)
    }

    private fun adjustSpeed() {
        val backlog = backlogMs()
        if (backlog > SKIP_BACKLOG_MS) {
            // Hopelessly behind: skip to the newest speech.
            while (queue.size > 1) queuedBytes.addAndGet(-(queue.pollFirst()?.size ?: 0).toLong())
        }
        val wanted = when {
            backlog > FAST_BACKLOG_MS -> FAST_SPEED
            backlog < NORMAL_BACKLOG_MS -> 1f
            else -> speed
        }
        if (wanted != speed) {
            try {
                track.playbackParams = PlaybackParams().setSpeed(wanted).setPitch(1f)
                speed = wanted
            } catch (e: Exception) {
                Log.w(TAG, "Playback speed not supported", e)
            }
        }
    }

    private fun doFlush() {
        flushRequested = false
        track.pause()
        track.flush()
        framesWritten = 0
        track.play()
        setSpeaking(false)
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        main.post { onSpeaking(value) }
    }

    private companion object {
        const val TAG = "PcmPlayer"
        const val NORMAL_BACKLOG_MS = 1_500L
        const val FAST_BACKLOG_MS = 5_000L
        const val SKIP_BACKLOG_MS = 15_000L
        const val FAST_SPEED = 1.25f
    }
}
