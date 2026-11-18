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
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Foreground service that captures audio (device playback or microphone) and shows
 * translated captions in a floating overlay, optionally speaking the translation.
 *
 * - Offline / Gemini engines: Vosk transcribes on the phone, the text is translated (ML Kit,
 *   NLLB or Gemini), and the phone's text-to-speech voice reads finished sentences.
 * - Gemini Live engine: the audio itself goes to Gemini Live Translate, which returns
 *   translated speech plus transcripts of both sides.
 */
class CaptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clock = { SystemClock.elapsedRealtime() }
    private var started = false
    private var failed = false
    private lateinit var prefs: Prefs

    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var showOriginal = true
    private var useMic = false
    private var engine = TranslationEngine.OFFLINE
    private var target: TargetLanguage? = null
    private var sourceTag = "en"

    // Offline / Gemini text engines.
    private var translator: TextTranslator? = null
    private var pipeline: TranslationPipeline? = null
    private val original = CaptionLine()
    private val translated = CaptionLine()

    // Gemini Live engine.
    private var http: OkHttpClient? = null
    private var live: LiveTranslator? = null
    private val liveOriginal = RollingText(clock = clock)
    private val liveTranslated = RollingText(clock = clock)

    // Spoken translation.
    private var headphones: HeadphoneMonitor? = null
    private var focus: VoiceFocus? = null
    private var tts: TtsVoice? = null
    private var ttsLookup: Job? = null
    private var player: PcmPlayer? = null
    private var voiceOn = false
    private var voiceSpeaking = false

    /** Microphone + phone speaker: the microphone would hear our own voice, so it is muted while we speak. */
    @Volatile private var muteInput = false
    private var warnedRateLimit = false
    private var warnedError = false

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

        prefs = Prefs(this)
        useMic = prefs.useMicrophone
        showOriginal = prefs.showOriginal
        engine = prefs.engine
        voiceOn = prefs.voiceEnabled

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
        sourceTag = language.translateTag
        target = TargetLanguage.catalog(TranslateLanguage.getAllLanguages())
            .firstOrNull { it.tag == prefs.targetTag }
            // Translating into the spoken language is pointless (Gemini Live detects the language itself).
            ?.takeIf { engine == TranslationEngine.LIVE || it.tag != language.translateTag }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val projectionData = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)

        scope.launch {
            try {
                run(language, resultCode, projectionData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(language: SpeechLanguage, resultCode: Int, projectionData: Intent?) {
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
            if (record.state != AudioRecord.STATE_INITIALIZED) throw IOException(getString(R.string.audio_open_failed))
            overlay = CaptionOverlay(this, prefs.textSizeSp, onClose = { stopSelf() }, onVoiceToggle = ::toggleVoice)
                .also { it.show() }
            startVoice()
            if (engine == TranslationEngine.LIVE) runLive(record) else runText(record, language)
        } finally {
            record.release()
        }
    }

    // region Offline / Gemini text engines

    private suspend fun runText(record: AudioRecord, language: SpeechLanguage) {
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
            val t = target
            if (t != null) {
                if (!t.supports(engine)) throw IllegalStateException(getString(R.string.engine_unsupported_language))
                val textTranslator = when {
                    engine == TranslationEngine.GEMINI -> GeminiTranslator(
                        prefs.geminiApiKey, language.label, t,
                        connectingMessage = getString(R.string.status_connecting_translation),
                    )
                    t.tag == TargetLanguage.KHMER -> OfflineKhmerTranslator(this, language.translateTag)
                    else -> MlKitTranslator(this, language.translateTag, t.mlKit!!)
                }
                translator = textTranslator
                textTranslator.prepare(::status)
                pipeline = TranslationPipeline(scope, textTranslator, clock, pipelineListener)
                if (voiceOn) ensureTts()
            }
            running()
            recognize(record, model)
        } finally {
            model.close()
        }
    }

    /** Blocking audio → text loop; runs until the service is cancelled. */
    private suspend fun recognize(record: AudioRecord, model: Model) = withContext(Dispatchers.Default) {
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        try {
            record.startRecording()
            val buffer = ShortArray(SAMPLE_RATE / 5) // 200 ms
            val silence = ShortArray(buffer.size)
            var lastPartial = ""
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) throw IOException("Audio capture stopped (error $n)")
                if (n == 0) continue
                if (recognizer.acceptWaveForm(if (muteInput) silence else buffer, n)) {
                    lastPartial = ""
                    val text = parseVoskResult(recognizer.result, "text")
                    if (text.isNotEmpty()) withContext(Dispatchers.Main) { onFinal(text) }
                } else {
                    val partial = parseVoskResult(recognizer.partialResult, "partial")
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        lastPartial = partial
                        withContext(Dispatchers.Main) { onPartial(partial) }
                    }
                }
            }
        } finally {
            runCatching { record.stop() }
            recognizer.close()
        }
    }

    private fun onPartial(raw: String) {
        val text = TextTidy.transcript(raw, sourceTag).capitalizeFirst()
        original.setLive(text)
        pipeline?.submitPartial(text)
        render()
    }

    private fun onFinal(raw: String) {
        val sentence = TextTidy.sentence(raw, sourceTag)
        original.commit(sentence)
        pipeline?.submitFinal(sentence)
        render()
    }

    private val pipelineListener = object : TranslationPipeline.Listener {
        override fun onPartialTranslation(text: String) {
            translated.setLive(text)
            render()
        }

        override fun onFinalTranslation(line: TranslatedLine) {
            translated.commit(line.translation)
            render()
            if (voiceAllowed()) tts?.speak(line.translation)
        }

        override fun onRateLimited(retryAfterMs: Long) {
            Log.i(TAG, "Rate limited for $retryAfterMs ms")
            if (!warnedRateLimit) {
                warnedRateLimit = true
                toast(getString(R.string.rate_limited))
            }
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "Translation failed", e)
            if (!warnedError) {
                warnedError = true
                toast(getString(R.string.translation_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    // endregion

    // region Gemini Live engine

    private suspend fun runLive(record: AudioRecord) {
        val code = target?.live ?: throw IllegalStateException(getString(R.string.live_needs_target))
        status(getString(R.string.status_connecting_live))
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS) // Notice dead connections quickly and reconnect.
            .readTimeout(0, TimeUnit.MILLISECONDS) // Silence is normal on a live stream.
            .build()
            .also { http = it }
        val url = LiveTranslator.url(prefs.geminiApiKey)
        val translator = LiveTranslator(scope, { listener -> LiveTranslateSession(client, url, code, listener) }, liveEvents)
        live = translator
        translator.start()
        streamAudio(record, translator)
    }

    /** Sends the captured audio to Gemini Live in 100 ms chunks until the service is cancelled. */
    private suspend fun streamAudio(record: AudioRecord, translator: LiveTranslator) = withContext(Dispatchers.Default) {
        try {
            record.startRecording()
            val samples = ShortArray(SAMPLE_RATE / 10)
            while (isActive) {
                val n = record.read(samples, 0, samples.size)
                if (n < 0) throw IOException("Audio capture stopped (error $n)")
                if (n == 0) continue
                val bytes = ByteArray(n * 2)
                if (!muteInput) ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, n)
                translator.sendAudio(bytes)
            }
        } finally {
            runCatching { record.stop() }
        }
    }

    private val liveEvents = object : LiveTranslator.Events {
        override fun onConnected() {
            if (CaptionState.status.value != CaptionStatus.Running) running()
        }

        override fun onReconnecting() {
            overlay?.setStatus(getString(R.string.status_reconnecting_live))
        }

        override fun onInputTranscript(text: String, interim: Boolean) {
            if (interim) liveOriginal.setInterim(text) else liveOriginal.append(text)
            render()
        }

        override fun onOutputTranscript(text: String) {
            liveTranslated.append(text)
            render()
        }

        override fun onAudio(pcm: ByteArray, sampleRate: Int) {
            if (!voiceAllowed()) return
            val current = player
            val p = if (current != null && current.sampleRate == sampleRate) current else {
                current?.release()
                PcmPlayer(sampleRate, ::onSpeakingChanged).also { player = it }
            }
            p.enqueue(pcm)
        }

        override fun onTurnComplete() {
            liveOriginal.endSegment()
            liveTranslated.endSegment()
        }

        override fun onInterrupted() {
            player?.flush()
        }

        override fun onFatal(message: String) = fail(IOException(message))
    }

    // endregion

    // region Spoken translation

    private fun startVoice() {
        if (target == null) {
            overlay?.setVoiceButton(VoiceButton.HIDDEN)
            return
        }
        focus = VoiceFocus(this, prefs.duckOthers) { stopSpeaking() }
        val monitor = HeadphoneMonitor(this).also { headphones = it }
        monitor.start()
        scope.launch {
            monitor.connected.collect {
                if (!voiceAllowed()) stopSpeaking()
                updateInputMute()
                updateVoiceButton()
            }
        }
    }

    private val headphonesConnected get() = headphones?.connected?.value == true

    private fun voiceAllowed() = voiceOn && target != null && (headphonesConnected || prefs.allowSpeaker)

    private fun toggleVoice() {
        voiceOn = !voiceOn
        prefs.voiceEnabled = voiceOn
        if (!voiceOn) {
            stopSpeaking()
        } else {
            if (engine != TranslationEngine.LIVE && pipeline != null) ensureTts()
            if (!headphonesConnected && !prefs.allowSpeaker) toast(getString(R.string.voice_connect_headphones))
        }
        updateVoiceButton()
    }

    private fun updateVoiceButton() {
        overlay?.setVoiceButton(
            when {
                target == null -> VoiceButton.HIDDEN
                !voiceOn -> VoiceButton.OFF
                !headphonesConnected && !prefs.allowSpeaker -> VoiceButton.WAITING_FOR_HEADPHONES
                else -> VoiceButton.ON
            },
        )
    }

    /** Finds a text-to-speech voice for the target language (Offline / Gemini engines). */
    private fun ensureTts() {
        val t = target ?: return
        if (tts != null || ttsLookup?.isActive == true) return
        ttsLookup = scope.launch {
            when (val result = TtsVoice.find(this@CaptionService, t.voiceLocale)) {
                is VoiceLookup.Ready -> tts = TtsVoice(result.tts, prefs.voiceRate, ::onSpeakingChanged)
                is VoiceLookup.NeedsDownload -> toast(getString(R.string.voice_needs_download, t.displayName()))
                VoiceLookup.Unsupported -> toast(getString(R.string.voice_unsupported, t.displayName()))
            }
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        player?.flush()
    }

    private fun onSpeakingChanged(speaking: Boolean) {
        voiceSpeaking = speaking
        focus?.setSpeaking(speaking)
        updateInputMute()
    }

    private fun updateInputMute() {
        muteInput = useMic && voiceSpeaking && !headphonesConnected
    }

    // endregion

    private fun render() {
        val overlay = overlay ?: return
        when {
            engine == TranslationEngine.LIVE -> {
                val translation = liveTranslated.render()
                val source = liveOriginal.render()
                if (translation.isEmpty()) overlay.setCaption(source, "")
                else overlay.setCaption(translation, if (showOriginal) source else "")
            }
            translator == null -> overlay.setCaption(original.render(), "")
            else -> overlay.setCaption(translated.render(), if (showOriginal) original.render() else "")
        }
    }

    private fun running() {
        CaptionState.status.value = CaptionStatus.Running
        overlay?.setStatus(getString(R.string.listening))
        updateVoiceButton()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service is started.
    private fun createPlaybackRecord(projection: MediaProjection): AudioRecord {
        val builder = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
        // Our own spoken translation must never be captioned again. Text-to-speech engines play
        // in their own app, so exclude those too when the phone's voice may be used.
        builder.excludeUid(Process.myUid())
        if (engine != TranslationEngine.LIVE && target != null) {
            VoiceAudio.ttsEngineUids(this).filter { it != Process.myUid() }.forEach { builder.excludeUid(it) }
        }
        return AudioRecord.Builder()
            .setAudioFormat(audioFormat())
            .setBufferSizeInBytes(bufferBytes())
            .setAudioPlaybackCaptureConfig(builder.build())
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun fail(e: Throwable) {
        Log.e(TAG, "Caption service failed", e)
        failed = true
        CaptionState.status.value = CaptionStatus.Error(e.message ?: e.javaClass.simpleName)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        live?.stop()
        pipeline?.close()
        overlay?.hide()
        translator?.close()
        tts?.close()
        player?.release()
        focus?.abandon()
        headphones?.stop()
        http?.let {
            it.dispatcher.executorService.shutdown()
            it.connectionPool.evictAll()
        }
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
