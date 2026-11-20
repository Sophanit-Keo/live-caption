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
import android.media.audiofx.LoudnessEnhancer
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
class VoiceFocus(context: Context, var duckOthers: Boolean, private val onLost: () -> Unit) {
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

/**
 * Speaks translated sentences with the phone's text-to-speech engine. The next sentence is
 * handed to the engine while the current one is still playing, so there is no gap between
 * them; anything beyond that waits in a [SpeechQueue] that keeps only the newest sentences.
 */
class TtsVoice(
    private val tts: TextToSpeech,
    rate: Float,
    private val onSpeaking: (Boolean) -> Unit,
) {
    /** Normal speech rate chosen by the user (1 = normal); can change while speaking. */
    var baseRate: Float = rate

    /** Speak lower for men and higher for women and children. */
    var matchVoice: Boolean = true

    private class Utterance(val text: String, val voice: VoiceType)

    private val queue = SpeechQueue<Utterance> { it.text.isBlank() }
    private val main = Handler(Looper.getMainLooper())
    private val inEngine = LinkedHashSet<String>()
    private var nextId = 0

    init {
        tts.setAudioAttributes(VoiceAudio.attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { finished(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { finished(utteranceId) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { finished(utteranceId) }
            }
        })
    }

    /** Main thread only. [voice] is who said the sentence, to speak it in a matching pitch. */
    fun speak(text: String, voice: VoiceType = VoiceType.UNKNOWN) {
        queue.add(Utterance(text, voice))
        pump()
    }

    private fun finished(id: String?) {
        if (id != null && inEngine.remove(id)) pump()
    }

    private fun pump() {
        while (inEngine.size < IN_ENGINE) {
            val next = queue.next() ?: break
            val id = "caption-${nextId++}"
            // Faster while sentences are waiting, so the voice doesn't fall behind the video.
            tts.setSpeechRate(baseRate * queue.rateFactor(alsoQueued = inEngine.size))
            tts.setPitch(if (matchVoice) pitchFor(next.voice) else 1f)
            if (tts.speak(next.text, TextToSpeech.QUEUE_ADD, Bundle(), id) == TextToSpeech.SUCCESS) inEngine += id
        }
        onSpeaking(inEngine.isNotEmpty())
    }

    /** Stops talking and forgets queued sentences. */
    fun stop() {
        queue.clear()
        inEngine.clear()
        tts.stop()
        onSpeaking(false)
    }

    fun close() {
        stop()
        tts.shutdown()
    }

    companion object {
        /** Sentences handed to the engine at once: the one playing and the next one. */
        private const val IN_ENGINE = 2

        /**
         * Pitch for the phone's voice by who is speaking. Android doesn't say whether a voice is
         * male or female, so the same voice is made lower or higher.
         */
        fun pitchFor(voice: VoiceType): Float = when (voice) {
            VoiceType.MAN -> 0.82f
            VoiceType.WOMAN -> 1.12f
            VoiceType.CHILD -> 1.4f
            VoiceType.UNKNOWN -> 1f
        }
    }
}

/** Looking up a phone voice. */
object PhoneVoices {
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
            else -> {
                pickBestVoice(tts, locale)
                VoiceLookup.Ready(tts, pkg, label(pkg))
            }
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

    /**
     * Prefers a voice that is installed on the phone (no network delay before each
     * sentence) and of the highest quality, in the requested country when possible.
     */
    private fun pickBestVoice(tts: TextToSpeech, locale: Locale) {
        val voices = try {
            tts.voices.orEmpty()
        } catch (e: Exception) {
            return
        }
        val best = voices
            .filter { it.locale.language == locale.language }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .sortedWith(
                compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenByDescending { locale.country.isNotEmpty() && it.locale.country == locale.country },
            )
            .firstOrNull() ?: return
        try {
            tts.voice = best
        } catch (e: Exception) {
            Log.w("TtsVoice", "Could not select voice ${best.name}", e)
        }
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

/**
 * Plays the stream of translated speech from Gemini Live (16-bit mono PCM) as it arrives.
 *
 * - Each phrase starts after a short buffer, so network hiccups don't make the voice stutter.
 * - When translated speech piles up behind the video, playback speeds up in small steps
 *   ([PlaybackPacing]); far behind, the oldest speech is skipped.
 * - Optionally boosts loudness so the translation stands out over the original audio.
 */
class PcmPlayer(val sampleRate: Int, boost: Boolean, private val onSpeaking: (Boolean) -> Unit) {
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
        // A small device buffer: speed changes and skips take effect quickly.
        .setBufferSizeInBytes(
            maxOf(
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
                bytesFor(DEVICE_BUFFER_MS),
            ),
        )
        .build()

    private val enhancer: LoudnessEnhancer? = try {
        LoudnessEnhancer(track.audioSessionId).apply {
            setTargetGain(BOOST_MILLIBELS)
            enabled = boost
        }
    } catch (e: Exception) {
        Log.w(TAG, "Loudness boost not available", e)
        null
    }

    private val queue = LinkedBlockingDeque<ByteArray>()
    private val queuedBytes = AtomicLong()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = true
    @Volatile private var flushRequested = false
    @Volatile private var framesWritten = 0L
    private var speaking = false
    @Volatile private var speedStep = 0

    /** Current playback speed (1 = normal). */
    val speed: Float get() = PlaybackPacing.SPEEDS[speedStep]

    private val writer = thread(name = "translation-voice") {
        track.play()
        while (running) {
            if (flushRequested) doFlush()
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS)
            if (chunk != null) {
                if (!speaking) waitForStartBuffer(chunk.size)
                queuedBytes.addAndGet(-chunk.size.toLong())
                setSpeaking(true)
                var offset = 0
                while (offset < chunk.size && running && !flushRequested) {
                    val n = track.write(chunk, offset, chunk.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                framesWritten += offset / 2
                pace()
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

    fun setBoost(on: Boolean) {
        try {
            enhancer?.enabled = on
        } catch (e: Exception) {
            Log.w(TAG, "Loudness boost failed", e)
        }
    }

    fun release() {
        running = false
        writer.join(1_000)
        enhancer?.release()
        track.release()
    }

    /** Milliseconds of translated speech waiting to be heard (any thread). */
    fun backlogMs(): Long = (queuedBytes.get().coerceAtLeast(0) / 2 + unplayedFrames()) * 1000 / sampleRate

    private fun unplayedFrames(): Long {
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (framesWritten - head).coerceAtLeast(0)
    }

    /** At the start of a phrase, wait briefly for a little more audio to arrive. */
    private fun waitForStartBuffer(firstChunkBytes: Int) {
        val deadline = System.nanoTime() + START_WAIT_MS * 1_000_000
        while (running && !flushRequested && queuedBytes.get() < bytesFor(START_BUFFER_MS).toLong() - firstChunkBytes &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
        }
    }

    private fun pace() {
        if (backlogMs() > PlaybackPacing.SKIP_ABOVE_MS) {
            // Hopelessly behind the video: skip the oldest speech.
            while (queue.size > 1 && backlogMs() > PlaybackPacing.CATCH_UP_TO_MS) {
                queuedBytes.addAndGet(-(queue.pollFirst()?.size ?: 0).toLong())
            }
        }
        val step = PlaybackPacing.step(backlogMs(), speedStep)
        if (step != speedStep) {
            try {
                track.playbackParams = PlaybackParams().setSpeed(PlaybackPacing.SPEEDS[step]).setPitch(1f)
                speedStep = step
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

    private fun bytesFor(ms: Int) = sampleRate * 2 * ms / 1000

    private companion object {
        const val TAG = "PcmPlayer"
        const val DEVICE_BUFFER_MS = 200
        const val START_BUFFER_MS = 240
        const val START_WAIT_MS = 300L
        const val BOOST_MILLIBELS = 600 // +6 dB
    }
}
