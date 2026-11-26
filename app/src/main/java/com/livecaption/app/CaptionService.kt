package com.livecaption.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException

/**
 * Foreground service that captures audio (device playback or microphone),
 * transcribes it on-device with Vosk, translates it (ML Kit, offline NLLB or Gemini),
 * renders the result in a floating overlay, and can read the translation aloud in headphones.
 */
class CaptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var translator: TextTranslator? = null
    private var speaker: TranslationSpeaker? = null
    private var showOriginal = true

    private val original = CaptionLine()
    private val translated = CaptionLine()

    /** The latest finished sentences and their translations, sent as context to Gemini. */
    private val history = ArrayDeque<TranslatedLine>()

    /** Set when the translation service asks us to wait; nothing is sent before then. */
    private var pausedUntil = 0L

    /** After a rate-limit error the sentence in progress is not translated for a while. */
    private var partialsPausedUntil = 0L

    /** Bumped at every finished sentence so late partial translations are dropped. */
    private var utterance = 0
    private val pendingPartial = MutableStateFlow(0 to "")
    private val pendingFinals = Channel<String>(Channel.UNLIMITED)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        val prefs = Prefs(this)
        val useMic = prefs.useMicrophone
        showOriginal = prefs.showOriginal

        // Must be in the foreground (with the right type) before touching MediaProjection.
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (useMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            fail(e)
            return START_NOT_STICKY
        }

        val language = SpeechLanguage.byModelName(prefs.sourceModel)
        val targetTag = prefs.targetTag.takeIf { it.isNotEmpty() && it != language.translateTag }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)

        scope.launch {
            try {
                run(
                    language, targetTag, prefs.useGemini, prefs.cloudApiKey, useMic, resultCode, projectionData,
                    prefs.textSizeSp, if (prefs.speakTranslation) prefs.voiceSettings else null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(
        language: SpeechLanguage,
        targetTag: String?,
        useGemini: Boolean,
        cloudApiKey: String,
        useMic: Boolean,
        resultCode: Int,
        projectionData: Intent?,
        textSizeSp: Float,
        /** Null when the translation should not be spoken. */
        voice: VoiceSettings?,
    ) {
        // Claim the capture session right away: the consent token expires if left unused.
        val record = if (useMic) {
            createMicRecord()
        } else {
            val data = projectionData ?: throw IllegalStateException(getString(R.string.capture_denied))
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
                ?: throw IllegalStateException(getString(R.string.capture_denied))
            projection = mp
            mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            createPlaybackRecord(mp)
        }

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException("Could not open the audio source")

            val overlay = CaptionOverlay(this, textSizeSp) { stopSelf() }.also { this.overlay = it }
            overlay.show()

            val modelDir = ModelManager.ensure(this, language) { percent ->
                status(
                    if (percent >= 0) getString(R.string.status_downloading_speech, percent)
                    else getString(R.string.status_unpacking_speech),
                )
            }
            status(getString(R.string.status_loading_speech))
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val model = withContext(Dispatchers.IO) { Model(modelDir.absolutePath) }

            try {
                if (targetTag != null) {
                    translator = when {
                        useGemini -> GeminiTranslator(
                            this, cloudApiKey, languageName(language.translateTag), languageName(targetTag),
                        )
                        targetTag == KHMER -> OfflineKhmerTranslator(this, language.translateTag)
                        else -> MlKitTranslator(this, language.translateTag, targetTag)
                    }.also { it.prepare(::status) }
                    if (voice != null) prepareSpeaker(targetTag, voice)
                    scope.launch { translateFinals() }
                    scope.launch { translatePartials() }
                }

                CaptionState.status.value = CaptionStatus.Running
                overlay.setStatus(getString(R.string.listening))
                recognize(record, model, language.translateTag)
            } finally {
                model.close()
            }
        } finally {
            record.release()
        }
    }

    /** Captions keep working without a voice, so a missing one is reported rather than fatal. */
    private suspend fun prepareSpeaker(targetTag: String, voice: VoiceSettings) {
        status(getString(R.string.status_loading_voice))
        val locale = TranslationSpeaker.localeFor(targetTag)
        val speaker = TranslationSpeaker(this, locale, voice).also { this.speaker = it }
        if (!speaker.prepare()) {
            speaker.close()
            this.speaker = null
            Toast.makeText(this, getString(R.string.no_voice, locale.displayLanguage), Toast.LENGTH_LONG).show()
        }
    }

    /** Blocking audio → text loop; runs until the service is cancelled. */
    private suspend fun recognize(record: AudioRecord, model: Model, languageTag: String) = withContext(Dispatchers.Default) {
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        try {
            record.startRecording()
            val buffer = ShortArray(SAMPLE_RATE / 5) // 200 ms
            var lastPartial = ""
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) throw IOException("Audio capture stopped (error $n)")
                if (n == 0) continue
                if (recognizer.acceptWaveForm(buffer, n)) {
                    lastPartial = ""
                    val text = parseVoskResult(recognizer.result, "text")
                    if (text.isNotEmpty()) withContext(Dispatchers.Main) { onFinal(Transcript.finish(text, languageTag)) }
                } else {
                    val partial = parseVoskResult(recognizer.partialResult, "partial")
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        lastPartial = partial
                        withContext(Dispatchers.Main) { onPartial(Transcript.tidy(partial, languageTag)) }
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            recognizer.close()
        }
    }

    private fun onPartial(text: String) {
        original.setLive(text)
        pendingPartial.value = utterance to text
        render()
    }

    private fun onFinal(text: String) {
        original.commit(text)
        utterance++
        pendingFinals.trySend(text)
        render()
    }

    /**
     * Translates finished sentences in order. Sentences that pile up while a translation
     * is running are sent together next time (or, for slow engines, only the newest), and
     * a busy or unreachable service is retried after a pause instead of losing the sentence.
     */
    private suspend fun translateFinals() {
        val translator = translator ?: return
        val waiting = ArrayList<String>()
        var failures = 0
        while (true) {
            if (waiting.isEmpty()) waiting += pendingFinals.receive()
            val pause = pausedUntil - SystemClock.elapsedRealtime()
            if (pause > 0) delay(pause)
            while (true) waiting += pendingFinals.tryReceive().getOrNull() ?: break

            val batch = waiting.takeLast(if (translator.translatesBacklog) MAX_BATCH else 1)
            val text = batch.joinToString(" ")
            val result = try {
                translator.translate(text, history.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed", e)
                val retry = e is TranslationException && e.retryable && ++failures <= MAX_RETRIES
                if (e is TranslationException && e.status == 429) {
                    // Out of requests for now: leave what is left for finished sentences.
                    partialsPausedUntil = SystemClock.elapsedRealtime() + PARTIALS_PAUSE_MS
                }
                if (retry) {
                    pauseTranslation((e as TranslationException).retryAfterMs ?: retryDelayMs(failures))
                } else {
                    waiting.clear()
                    failures = 0
                }
                continue
            }
            waiting.clear()
            failures = 0
            if (result.isEmpty()) continue
            history.addLast(TranslatedLine(text, result))
            while (history.size > GeminiTranslator.MAX_HISTORY) history.removeFirst()
            translated.commit(result)
            speaker?.speak(result)
            render()
        }
    }

    /** Translates the sentence in progress; always works on the newest partial. */
    private suspend fun translatePartials() {
        val translator = translator ?: return
        val interval = translator.partialIntervalMs ?: return
        pendingPartial.collect { (id, text) ->
            if (text.isEmpty() || id != utterance) return@collect
            // Finished sentences have priority while the service asks us to slow down.
            val now = SystemClock.elapsedRealtime()
            if (now < pausedUntil || now < partialsPausedUntil) return@collect
            val result = try {
                translator.translate(text, history.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live translation failed", e)
                if (e is TranslationException && e.status == 429) {
                    partialsPausedUntil = SystemClock.elapsedRealtime() + PARTIALS_PAUSE_MS
                    e.retryAfterMs?.let(::pauseTranslation)
                }
                return@collect
            }
            if (id == utterance) {
                translated.setLive(result)
                render()
            }
            delay(interval)
        }
    }

    private fun pauseTranslation(ms: Long) {
        pausedUntil = maxOf(pausedUntil, SystemClock.elapsedRealtime() + ms.coerceIn(0, MAX_PAUSE_MS))
    }

    private fun render() {
        val overlay = overlay ?: return
        if (translator == null) {
            overlay.setCaption(original.render(), "")
        } else {
            overlay.setCaption(translated.render(), if (showOriginal) original.render() else "")
        }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service is started.
    private fun createPlaybackRecord(projection: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferBytes())
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun createMicRecord(): AudioRecord =
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferBytes())
            .build()

    private fun audioFormat() = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun bufferBytes(): Int {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return maxOf(min, SAMPLE_RATE * 2) // at least one second
    }

    private fun status(message: String) {
        CaptionState.status.value = CaptionStatus.Preparing(message)
        overlay?.setStatus(message)
    }

    private fun fail(e: Throwable) {
        Log.e(TAG, "Caption service failed", e)
        failed = true
        CaptionState.status.value = CaptionStatus.Error(e.message ?: e.javaClass.simpleName)
        stopSelf()
    }

    private var failed = false

    override fun onDestroy() {
        scope.cancel()
        overlay?.hide()
        translator?.close()
        speaker?.close()
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        if (!failed) CaptionState.status.value = CaptionStatus.Idle
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caption)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        private const val TAG = "CaptionService"
        private const val SAMPLE_RATE = 16_000
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "captions"
        private const val ACTION_STOP = "com.livecaption.app.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_PROJECTION_DATA = "projectionData"

        /** Finished sentences sent in one request when several are waiting. */
        private const val MAX_BATCH = 3
        private const val MAX_RETRIES = 3
        private const val MAX_PAUSE_MS = 60_000L
        private const val PARTIALS_PAUSE_MS = 60_000L

        /** 1 s, 2 s, 4 s… between attempts when the service did not say how long to wait. */
        fun retryDelayMs(failures: Int): Long = 1_000L shl (failures - 1).coerceIn(0, 5)

        /** [projectionData] is the screen-capture consent result; null for microphone mode. */
        fun start(context: Context, resultCode: Int = Activity.RESULT_CANCELED, projectionData: Intent? = null) {
            CaptionState.status.value = CaptionStatus.Preparing("")
            val intent = Intent(context, CaptionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptionService::class.java))
        }
    }
}
