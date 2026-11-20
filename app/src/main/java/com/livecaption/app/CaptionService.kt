package com.livecaption.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import java.util.concurrent.Executors
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
    private var prefsWatcher: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var showOriginal = true
    private var useMic = false
    private var engine = TranslationEngine.OFFLINE
    private var target: TargetLanguage? = null
    private var sourceTag = "en"

    /** Paused from the captions' toolbar or the notification: audio is ignored. */
    @Volatile private var paused = false
    private var locked = false
    private var connecting = true

    // Offline / Gemini text engines.
    private var translator: TextTranslator? = null
    private var pipeline: TranslationPipeline? = null
    private val original = SentenceBuffer(clock = clock)
    private val translated = SentenceBuffer(clock = clock)

    // Gemini Live engine.
    private var http: OkHttpClient? = null
    private var live: LiveTranslator? = null
    private val liveOriginal = SentenceBuffer(clock = clock)
    private val liveTranslated = SentenceBuffer(clock = clock)
    private val captionTimer = CaptionScheduler(scope, clock)

    // Spoken translation.
    private var headphones: HeadphoneMonitor? = null
    private var focus: VoiceFocus? = null
    private var tts: TtsVoice? = null
    private var ttsLookup: Job? = null
    private var player: PcmPlayer? = null
    private var voiceOn = false
    private var voiceSpeaking = false

    // Transcript of this session. The writer is only used on [transcriptIo].
    private val transcriptIo = Executors.newSingleThreadExecutor()
    private var transcriptStore: TranscriptStore? = null
    private var transcriptWriter: TranscriptWriter? = null
    private var transcriptId: String? = null
    private var sessionStart = 0L
    private var sentenceStart: Long? = null

    /** Start/end times of finished sentences still being translated, oldest first. */
    private val sentenceTimes = ArrayDeque<LongArray>()
    private val liveInput = StringBuilder()
    private val liveOutput = StringBuilder()
    private var liveSegmentStart: Long? = null

    /** Microphone + phone speaker: the microphone would hear our own voice, so it is muted while we speak. */
    @Volatile private var muteInput = false
    private var warnedRateLimit = false
    private var warnedError = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (intent == null || action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_TOGGLE_PAUSE || action == ACTION_UNLOCK) {
            // Toolbar/notification commands for running captions; never start captions by themselves.
            if (!started) {
                stopSelf()
            } else if (action == ACTION_TOGGLE_PAUSE) {
                togglePause()
            } else {
                overlay?.setLocked(false)
            }
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
        prefsWatcher = prefs.watch(::onSettingChanged)
        startTranscript(language)

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
            overlay = CaptionOverlay(this, prefs, overlayActions).also { it.show() }
            updateIndicator()
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
                        topic = prefs.topic,
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
            var wasPaused = false
            while (isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) throw IOException("Audio capture stopped (error $n)")
                if (n == 0) continue
                if (paused) {
                    if (!wasPaused) {
                        // Finish the sentence in progress, so it is translated rather than lost.
                        wasPaused = true
                        lastPartial = ""
                        val text = parseVoskResult(recognizer.finalResult, "text")
                        if (text.isNotEmpty()) withContext(Dispatchers.Main) { onFinal(text) }
                    }
                    continue
                }
                wasPaused = false
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
        if (sentenceStart == null) sentenceStart = clock()
        val text = TextTidy.transcript(raw, sourceTag).capitalizeFirst()
        original.setPending(text)
        pipeline?.submitPartial(text)
        render()
    }

    private fun onFinal(raw: String) {
        val sentence = TextTidy.sentence(raw, sourceTag)
        val end = clock()
        val start = sentenceStart ?: (end - 1_000)
        sentenceStart = null
        original.commit(sentence)
        val p = pipeline
        if (p == null) {
            recordLine(sentence, null, start, end)
        } else {
            sentenceTimes.addLast(longArrayOf(start, end))
            p.submitFinal(sentence)
        }
        render()
    }

    /** Times of the next [count] translated sentences (merged translations cover several). */
    private fun takeSentenceTimes(count: Int): LongArray {
        val now = clock()
        val first = sentenceTimes.removeFirstOrNull() ?: return longArrayOf(now, now)
        var end = first[1]
        repeat(count - 1) { sentenceTimes.removeFirstOrNull()?.let { end = it[1] } }
        return longArrayOf(first[0], end)
    }

    private val pipelineListener = object : TranslationPipeline.Listener {
        override fun onPartialTranslation(text: String) {
            translated.setPending(text)
            render()
        }

        override fun onFinalTranslation(line: TranslatedLine) {
            translated.commit(line.translation)
            render()
            val times = takeSentenceTimes(line.parts)
            recordLine(line.source, line.translation, times[0], times[1])
            if (voiceAllowed() && !paused) tts?.speak(line.translation)
        }

        override fun onUntranslated(text: String) {
            val times = takeSentenceTimes(1)
            recordLine(text, null, times[0], times[1])
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
        val t = target ?: throw IllegalStateException(getString(R.string.live_needs_target))
        val code = t.live ?: throw IllegalStateException(getString(R.string.engine_unsupported_language))
        status(getString(R.string.status_connecting_live))
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS) // Notice dead connections quickly and reconnect.
            .readTimeout(0, TimeUnit.MILLISECONDS) // Silence is normal on a live stream.
            .build()
            .also { http = it }
        val url = LiveTranslator.url(prefs.geminiApiKey)
        val instruction = LiveTranslator.instructionFor(prefs.topic, t.englishName)
        val translator = LiveTranslator(
            scope,
            { listener -> LiveTranslateSession(client, url, code, listener, instruction) },
            liveEvents,
        )
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
                if (n == 0 || paused) continue
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
            connecting = false
            if (CaptionState.status.value != CaptionStatus.Running) running() else updateIndicator()
        }

        override fun onReconnecting() {
            connecting = true
            updateIndicator()
            overlay?.setStatus(getString(R.string.status_reconnecting_live))
        }

        override fun onInputTranscript(text: String, interim: Boolean) {
            if (interim) {
                liveOriginal.setPending(text)
            } else {
                liveOriginal.append(text)
                if (liveSegmentStart == null) liveSegmentStart = clock()
                liveInput.append(text)
                if (liveInput.length > MAX_LIVE_SEGMENT_CHARS) flushLiveSegment()
            }
            render()
        }

        override fun onOutputTranscript(text: String) {
            liveOutput.append(text)
            // Show the words when the voice says them, not seconds earlier.
            val p = player
            val delayMs = if (p != null && voiceAllowed() && prefs.syncCaptions) {
                (p.backlogMs() / p.speed).toLong().coerceAtMost(MAX_CAPTION_DELAY_MS)
            } else {
                0L
            }
            captionTimer.post(delayMs) {
                liveTranslated.append(text)
                render()
            }
        }

        override fun onAudio(pcm: ByteArray, sampleRate: Int) {
            if (!voiceAllowed() || paused) return
            val current = player
            val p = if (current != null && current.sampleRate == sampleRate) current else {
                current?.release()
                PcmPlayer(sampleRate, prefs.voiceBoost, ::onSpeakingChanged).also { player = it }
            }
            p.enqueue(pcm)
        }

        override fun onTurnComplete() {
            flushLiveSegment()
            liveOriginal.endSegment()
            captionTimer.post(0) { liveTranslated.endSegment() }
        }

        override fun onInterrupted() {
            player?.flush()
        }

        override fun onFatal(message: String) = fail(IOException(message))
    }

    // endregion

    // region Controls on the captions

    private val overlayActions = object : CaptionOverlay.Actions {
        override fun onClose() = stopSelf()

        override fun onVoiceToggle() = toggleVoice()

        override fun onPauseToggle() = togglePause()

        override fun onLockChanged(locked: Boolean) {
            this@CaptionService.locked = locked
            CaptionState.locked.value = locked
            updateNotification()
        }

        override fun onOpenSettings() {
            startActivity(
                Intent(this@CaptionService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        override fun onOpenTranscript() {
            val id = transcriptId
            val intent = if (id != null) TranscriptActivity.intent(this@CaptionService, id)
            else Intent(this@CaptionService, TranscriptsActivity::class.java)
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (id == null) toast(getString(R.string.transcripts_off))
        }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            stopSpeaking()
            live?.endAudioStream()
            flushLiveSegment()
            overlay?.setStatus(getString(R.string.overlay_paused))
        } else {
            overlay?.setStatus(getString(R.string.listening))
        }
        overlay?.setPaused(paused)
        updateIndicator()
        updateNotification()
    }

    /** Settings changed (on the captions' toolbar, or in the app while captions run). */
    private fun onSettingChanged(key: String?) {
        when (key) {
            in Prefs.APPEARANCE_KEYS -> {
                showOriginal = prefs.showOriginal
                overlay?.applyAppearance()
                render()
            }
            "voiceRate" -> tts?.baseRate = prefs.voiceRate
            "duckOthers" -> focus?.duckOthers = prefs.duckOthers
            "voiceBoost" -> player?.setBoost(prefs.voiceBoost)
            "allowSpeaker" -> {
                if (!voiceAllowed()) stopSpeaking()
                updateInputMute()
                updateVoiceButton()
            }
            "voiceEnabled" -> if (prefs.voiceEnabled != voiceOn) toggleVoice()
        }
    }

    private fun updateIndicator() {
        overlay?.setIndicator(
            when {
                paused -> CaptionIndicator.PAUSED
                connecting -> CaptionIndicator.CONNECTING
                voiceSpeaking -> CaptionIndicator.SPEAKING
                else -> CaptionIndicator.LISTENING
            },
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
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
        if (prefs.voiceEnabled != voiceOn) prefs.voiceEnabled = voiceOn
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
            when (val result = PhoneVoices.find(this@CaptionService, t.voiceLocale)) {
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
        if (voiceSpeaking == speaking) return
        voiceSpeaking = speaking
        focus?.setSpeaking(speaking)
        updateInputMute()
        updateIndicator()
    }

    private fun updateInputMute() {
        muteInput = useMic && voiceSpeaking && !headphonesConnected
    }

    // endregion

    // region Transcript

    private fun startTranscript(language: SpeechLanguage) {
        sessionStart = clock()
        if (!prefs.saveTranscripts) return
        val store = TranscriptStore(TranscriptActivity.storeDir(this)).also { transcriptStore = it }
        val now = System.currentTimeMillis()
        val id = store.newId(now)
        val info = TranscriptInfo(
            id = id,
            startedAt = now,
            engine = engine,
            sourceTag = if (engine == TranslationEngine.LIVE) TranscriptInfo.AUTO else language.translateTag,
            targetTag = target?.tag.orEmpty(),
            topic = if (engine.needsApiKey) prefs.topic.trim() else "",
        )
        transcriptId = id
        CaptionState.currentTranscript.value = id
        transcriptIo.execute {
            try {
                transcriptWriter = store.create(info)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the transcript", e)
            }
        }
    }

    /** Saves one finished line; times are [clock] values. */
    private fun recordLine(original: String, translation: String?, start: Long, end: Long) {
        if (transcriptId == null) return
        val line = TranscriptLine(
            startMs = (start - sessionStart).coerceAtLeast(0),
            endMs = (end - sessionStart).coerceAtLeast(0),
            original = original.trim(),
            translation = translation?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (line.original.isEmpty() && line.translation == null) return
        transcriptIo.execute {
            try {
                transcriptWriter?.append(line)
                CaptionState.transcriptVersion.value++
            } catch (e: Exception) {
                Log.w(TAG, "Could not save a transcript line", e)
            }
        }
    }

    /** Gemini Live: what was said (and its translation) since the last pause becomes one line. */
    private fun flushLiveSegment() {
        if (liveInput.isBlank() && liveOutput.isBlank()) return
        val now = clock()
        recordLine(liveInput.toString(), liveOutput.toString(), liveSegmentStart ?: now, now)
        liveInput.setLength(0)
        liveOutput.setLength(0)
        liveSegmentStart = null
    }

    private fun finishTranscript() {
        val id = transcriptId ?: run {
            transcriptIo.shutdown()
            return
        }
        val store = transcriptStore
        val endedAt = System.currentTimeMillis()
        transcriptIo.execute {
            try {
                val writer = transcriptWriter
                if (writer != null) {
                    val empty = writer.lineCount == 0
                    writer.finish(endedAt)
                    if (empty) store?.delete(id) // Nothing was said: don't keep an empty transcript.
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not finish the transcript", e)
            }
            transcriptWriter = null
            CaptionState.transcriptVersion.value++
        }
        transcriptIo.shutdown()
        transcriptId = null // Nothing more is written after this.
        CaptionState.currentTranscript.value = null
    }

    // endregion

    private fun render() {
        val overlay = overlay ?: return
        when {
            engine == TranslationEngine.LIVE -> {
                val translation = liveTranslated.snapshot()
                val source = liveOriginal.snapshot()
                if (translation.isEmpty) overlay.setCaption(source, null)
                else overlay.setCaption(translation, source.takeIf { showOriginal })
            }
            translator == null -> overlay.setCaption(original.snapshot(), null)
            else -> overlay.setCaption(translated.snapshot(), original.snapshot().takeIf { showOriginal })
        }
    }

    private fun running() {
        connecting = false
        CaptionState.status.value = CaptionStatus.Running
        overlay?.setStatus(getString(if (paused) R.string.overlay_paused else R.string.listening))
        updateVoiceButton()
        updateIndicator()
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
        flushLiveSegment()
        finishTranscript()
        scope.cancel()
        prefsWatcher?.let { prefs.unwatch(it) }
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
        CaptionState.locked.value = false
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_caption)
            .setContentTitle(getString(if (paused) R.string.notification_paused else R.string.notification_title))
            .setContentText(getString(if (locked) R.string.notification_locked else R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (locked) builder.addAction(0, getString(R.string.control_unlock), serviceIntent(ACTION_UNLOCK, 3))
        builder.addAction(
            0, getString(if (paused) R.string.control_resume else R.string.control_pause), serviceIntent(ACTION_TOGGLE_PAUSE, 2),
        )
        builder.addAction(0, getString(R.string.stop), serviceIntent(ACTION_STOP, 1))
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, CaptionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "CaptionService"
        private const val SAMPLE_RATE = 16_000
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "captions"
        private const val ACTION_STOP = "com.livecaption.app.STOP"
        private const val ACTION_TOGGLE_PAUSE = "com.livecaption.app.TOGGLE_PAUSE"
        private const val ACTION_UNLOCK = "com.livecaption.app.UNLOCK"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_PROJECTION_DATA = "projectionData"

        /** A Gemini Live transcript line is cut here if the speaker never pauses. */
        private const val MAX_LIVE_SEGMENT_CHARS = 600

        /** Translated text appears at most this much later than it arrives (to match the voice). */
        private const val MAX_CAPTION_DELAY_MS = 4_000L

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

        /** Makes locked captions touchable again. */
        fun unlock(context: Context) {
            context.startService(Intent(context, CaptionService::class.java).setAction(ACTION_UNLOCK))
        }
    }
}
