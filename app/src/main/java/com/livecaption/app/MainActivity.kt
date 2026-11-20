package com.livecaption.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.nl.translate.TranslateLanguage
import com.livecaption.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val catalog by lazy { TargetLanguage.catalog(TranslateLanguage.getAllLanguages()) }

    /** Entries of the "Translate to" list; null is "no translation". */
    private var targets: List<TargetLanguage?> = emptyList()

    /** Text-to-speech instance used to check and preview the voice for the chosen language. */
    private var previewTts: TextToSpeech? = null
    private var voiceCheck: Job? = null

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) startCaptions()
            else toast(R.string.need_mic_permission)
        }

    private val requestCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) CaptionService.start(this, result.resultCode, data)
            else toast(R.string.capture_denied)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        bindSettings()
        binding.toggle.setOnClickListener {
            if (CaptionState.status.value.isActive) CaptionService.stop(this) else startCaptions()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptionState.status.collect(::showStatus)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The user may have just installed a voice in the text-to-speech settings.
        refreshVoice()
        // The captions' toolbar may have changed the look meanwhile.
        loadSubtitleSettings()
        // Opening the app is a way out of locked captions.
        if (CaptionState.locked.value) CaptionService.unlock(this)
    }

    override fun onStop() {
        releasePreview()
        super.onStop()
    }

    private fun bindSettings() = with(binding) {
        engine.check(
            when (prefs.engine) {
                TranslationEngine.OFFLINE -> R.id.engineOffline
                TranslationEngine.GEMINI -> R.id.engineGemini
                TranslationEngine.LIVE -> R.id.engineLive
            },
        )
        engine.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.engine = when (checkedId) {
                R.id.engineGemini -> TranslationEngine.GEMINI
                R.id.engineLive -> TranslationEngine.LIVE
                else -> TranslationEngine.OFFLINE
            }
            onEngineChanged()
        }

        apiKey.setText(prefs.geminiApiKey)
        apiKey.doAfterTextChanged { prefs.geminiApiKey = it?.toString()?.trim().orEmpty() }
        topic.setText(prefs.topic)
        topic.doAfterTextChanged { prefs.topic = it?.toString().orEmpty() }

        sourceLanguage.adapter = dropdown(SpeechLanguage.ALL)
        sourceLanguage.setSelection(SpeechLanguage.ALL.indexOf(SpeechLanguage.byModelName(prefs.sourceModel)))
        sourceLanguage.onSelected { prefs.sourceModel = SpeechLanguage.ALL[it].modelName }

        targetLanguage.onSelected {
            prefs.targetTag = targets.getOrNull(it)?.tag.orEmpty()
            updateHints()
            refreshVoice()
            updatePreview()
        }

        audioSource.check(if (prefs.useMicrophone) R.id.sourceMic else R.id.sourceDevice)
        audioSourceHint.setText(if (prefs.useMicrophone) R.string.source_mic_hint else R.string.source_device_hint)
        audioSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.useMicrophone = checkedId == R.id.sourceMic
            audioSourceHint.setText(if (prefs.useMicrophone) R.string.source_mic_hint else R.string.source_device_hint)
        }

        voiceEnabled.isChecked = prefs.voiceEnabled
        voiceEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.voiceEnabled = checked
            refreshVoice()
        }
        voiceRate.value = prefs.voiceRate.coerceIn(voiceRate.valueFrom, voiceRate.valueTo)
        voiceRate.addOnChangeListener { _, value, _ -> prefs.voiceRate = value }
        duckOthers.isChecked = prefs.duckOthers
        duckOthers.setOnCheckedChangeListener { _, checked -> prefs.duckOthers = checked }
        allowSpeaker.isChecked = prefs.allowSpeaker
        allowSpeaker.setOnCheckedChangeListener { _, checked -> prefs.allowSpeaker = checked }
        voiceBoost.isChecked = prefs.voiceBoost
        voiceBoost.setOnCheckedChangeListener { _, checked -> prefs.voiceBoost = checked }
        testVoice.setOnClickListener { previewVoice() }

        subtitleStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.subtitleStyle = when (checkedId) {
                R.id.styleOutline -> SubtitleStyle.OUTLINE
                R.id.styleContrast -> SubtitleStyle.CONTRAST
                else -> SubtitleStyle.BOX
            }
            updatePreview()
        }
        alignment.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.centered = checkedId == R.id.alignCenter
            updatePreview()
        }
        textSize.addOnChangeListener { _, value, fromUser -> if (fromUser) setAppearance { prefs.textSizeSp = value } }
        boxOpacity.addOnChangeListener { _, value, fromUser -> if (fromUser) setAppearance { prefs.boxOpacity = value } }
        captionLines.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setAppearance { prefs.captionLines = value.toInt() }
        }
        showOriginal.setOnCheckedChangeListener { _, checked -> setAppearance { prefs.showOriginal = checked } }
        sentenceMode.setOnCheckedChangeListener { _, checked -> setAppearance { prefs.sentenceMode = checked } }
        syncCaptions.setOnCheckedChangeListener { _, checked -> prefs.syncCaptions = checked }
        saveTranscripts.isChecked = prefs.saveTranscripts
        saveTranscripts.setOnCheckedChangeListener { _, checked -> prefs.saveTranscripts = checked }
        openTranscripts.setOnClickListener { startActivity(Intent(this@MainActivity, TranscriptsActivity::class.java)) }
        loadSubtitleSettings()

        onEngineChanged()
    }

    private inline fun setAppearance(change: () -> Unit) {
        change()
        updatePreview()
    }

    /** Puts the subtitle settings on screen (they may also be changed from the captions' toolbar). */
    private fun loadSubtitleSettings() = with(binding) {
        subtitleStyle.check(
            when (prefs.subtitleStyle) {
                SubtitleStyle.BOX -> R.id.styleBox
                SubtitleStyle.OUTLINE -> R.id.styleOutline
                SubtitleStyle.CONTRAST -> R.id.styleContrast
            },
        )
        alignment.check(if (prefs.centered) R.id.alignCenter else R.id.alignLeft)
        textSize.value = prefs.textSizeSp.coerceIn(textSize.valueFrom, textSize.valueTo)
        boxOpacity.value = (Math.round(prefs.boxOpacity * 20) / 20f).coerceIn(boxOpacity.valueFrom, boxOpacity.valueTo)
        captionLines.value = prefs.captionLines.toFloat().coerceIn(captionLines.valueFrom, captionLines.valueTo)
        showOriginal.isChecked = prefs.showOriginal
        sentenceMode.isChecked = prefs.sentenceMode
        syncCaptions.isChecked = prefs.syncCaptions
        updatePreview()
    }

    /** Draws the sample subtitles with the current settings. */
    private fun updatePreview() = with(binding) {
        val appearance = prefs.appearance()
        preview.apply(appearance)
        // The background slider only matters for the box style.
        val boxStyle = appearance.style == SubtitleStyle.BOX
        boxOpacity.isEnabled = boxStyle
        backgroundLabel.alpha = if (boxStyle) 1f else 0.5f
        // Two sentences, so both subtitle layouts can be seen.
        val target = selectedTarget()
        val english = SentenceSnapshot(listOf(getString(R.string.preview_first)), getString(R.string.preview_second))
        val sample = target?.let { PREVIEW_SAMPLES[it.tag] }?.let { (first, second) -> SentenceSnapshot(listOf(first), second) }
        if (target == null) preview.setCaption(english, null) else preview.setCaption(sample ?: english, english)
    }

    private fun onEngineChanged() = with(binding) {
        val current = prefs.engine
        engineHint.setText(
            when (current) {
                TranslationEngine.OFFLINE -> R.string.engine_hint_offline
                TranslationEngine.GEMINI -> R.string.engine_hint_gemini
                TranslationEngine.LIVE -> R.string.engine_hint_live
            },
        )
        onlineSettings.isVisible = current.needsApiKey
        voiceBoost.isVisible = current == TranslationEngine.LIVE
        syncCaptions.isVisible = current == TranslationEngine.LIVE
        // Gemini Live recognises the spoken language by itself.
        sourceLanguage.isVisible = current != TranslationEngine.LIVE
        sourceAuto.isVisible = current == TranslationEngine.LIVE

        val locale = resources.configuration.locales[0]
        val supported = catalog.filter { it.supports(current) }.sortedBy { it.displayName(locale) }
        targets = (if (current == TranslationEngine.LIVE) emptyList() else listOf(null)) + supported
        targetLanguage.adapter = dropdown(targets.map { it?.displayName(locale) ?: getString(R.string.no_translation) })
        val saved = targets.indexOfFirst { it?.tag == prefs.targetTag }
        val index = when {
            saved >= 0 -> saved
            current == TranslationEngine.LIVE -> targets.indexOfFirst { it?.tag == TargetLanguage.KHMER }.coerceAtLeast(0)
            else -> 0
        }
        targetLanguage.setSelection(index)
        prefs.targetTag = targets.getOrNull(index)?.tag.orEmpty()
        updateHints()
        refreshVoice()
    }

    private fun updateHints() {
        binding.offlineKhmerHint.isVisible =
            prefs.engine == TranslationEngine.OFFLINE && prefs.targetTag == TargetLanguage.KHMER
    }

    private fun selectedTarget(): TargetLanguage? = catalog.firstOrNull { it.tag == prefs.targetTag }

    /** Shows how the translation will be spoken, and checks that a voice exists for the language. */
    private fun refreshVoice() = with(binding) {
        voiceCheck?.cancel()
        releasePreview()
        val target = selectedTarget()
        voiceSettings.isVisible = prefs.voiceEnabled
        ttsSettings.isVisible = false
        when {
            target == null -> voiceHint.setText(R.string.voice_hint_no_target)
            prefs.engine == TranslationEngine.LIVE -> voiceHint.setText(R.string.voice_hint_live)
            else -> {
                voiceHint.setText(R.string.voice_hint_tts)
                if (prefs.voiceEnabled) checkPhoneVoice(target)
            }
        }
    }

    private fun checkPhoneVoice(target: TargetLanguage) = with(binding) {
        val name = target.displayName(resources.configuration.locales[0])
        ttsSettings.isVisible = true
        voiceStatus.text = getString(R.string.voice_checking, name)
        installVoice.isVisible = false
        testVoice.isVisible = false
        voiceCheck = lifecycleScope.launch {
            when (val result = PhoneVoices.find(this@MainActivity, target.voiceLocale)) {
                is VoiceLookup.Ready -> {
                    previewTts = result.tts
                    voiceStatus.text = getString(R.string.voice_ready, result.engineLabel)
                    testVoice.isVisible = true
                }
                is VoiceLookup.NeedsDownload -> {
                    voiceStatus.text = getString(R.string.voice_missing, name)
                    installVoice.setText(R.string.voice_install)
                    installVoice.setOnClickListener { installVoiceData(result.enginePackage) }
                    installVoice.isVisible = true
                }
                VoiceLookup.Unsupported -> {
                    voiceStatus.text = getString(R.string.voice_none, name)
                    installVoice.setText(R.string.voice_get_google)
                    installVoice.setOnClickListener { openStore(GOOGLE_TTS_PACKAGE) }
                    installVoice.isVisible = true
                }
            }
        }
    }

    private fun previewVoice() {
        val tts = previewTts ?: return
        val target = selectedTarget() ?: return
        tts.setAudioAttributes(VoiceAudio.attributes)
        tts.setSpeechRate(prefs.voiceRate)
        tts.speak(voiceSample(target), TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    private fun releasePreview() {
        previewTts?.shutdown()
        previewTts = null
    }

    private fun installVoiceData(enginePackage: String) {
        try {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(enginePackage))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (e2: ActivityNotFoundException) {
                toast(R.string.voice_settings_missing)
            }
        }
    }

    private fun openStore(packageName: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
        }
    }

    private fun startCaptions() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        // Notifications are optional; only the microphone permission blocks starting.
        if (Manifest.permission.RECORD_AUDIO in needed) {
            requestPermissions.launch(needed.toTypedArray())
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.need_overlay_permission)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            return
        }
        if (prefs.engine.needsApiKey && prefs.geminiApiKey.isEmpty()) {
            toast(R.string.need_api_key)
            return
        }
        if (prefs.engine == TranslationEngine.LIVE && selectedTarget() == null) {
            toast(R.string.live_needs_target)
            return
        }
        releasePreview()
        if (prefs.useMicrophone) {
            CaptionService.start(this)
        } else {
            val manager = getSystemService(MediaProjectionManager::class.java)
            // Capture the whole device (not a single app) so captions follow the user across apps.
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            requestCapture.launch(intent)
        }
    }

    private fun showStatus(status: CaptionStatus) = with(binding) {
        toggle.setText(if (status.isActive) R.string.stop else R.string.start)
        progress.visibility = if (status is CaptionStatus.Preparing) View.VISIBLE else View.INVISIBLE
        this.status.text = when (status) {
            CaptionStatus.Idle -> getString(R.string.status_idle)
            CaptionStatus.Running -> getString(R.string.status_running)
            is CaptionStatus.Preparing -> status.message
            is CaptionStatus.Error -> getString(R.string.status_error, status.message)
        }
        // Settings are read when captions start, so lock them while running.
        val editable = !status.isActive
        // Looks and voice settings apply to running captions at once.
        listOf(
            engineOffline, engineGemini, engineLive, apiKeyLayout, topicLayout, sourceLanguage, targetLanguage,
            sourceDevice, sourceMic, saveTranscripts,
        ).forEach { it.isEnabled = editable }
    }

    private val CaptionStatus.isActive get() = this is CaptionStatus.Preparing || this is CaptionStatus.Running

    private fun <T> dropdown(items: List<T>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun AdapterView<*>.onSelected(action: (Int) -> Unit) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

        /** A short sentence to preview the voice: "Hello! This is your translation voice." */
        val SAMPLES = mapOf(
            "km" to "សួស្តី! នេះជាសំឡេងបកប្រែរបស់អ្នក។",
            "en" to "Hello! This is your translation voice.",
            "es" to "¡Hola! Esta es tu voz de traducción.",
            "fr" to "Bonjour ! Voici votre voix de traduction.",
            "de" to "Hallo! Das ist deine Übersetzungsstimme.",
            "zh" to "你好！这是你的翻译语音。",
            "ja" to "こんにちは！これはあなたの翻訳音声です。",
            "ko" to "안녕하세요! 번역 음성입니다.",
            "vi" to "Xin chào! Đây là giọng dịch của bạn.",
            "th" to "สวัสดี! นี่คือเสียงแปลของคุณ",
        )

        /** "Hello everyone." and "How are you today?" for the subtitle preview. */
        val PREVIEW_SAMPLES = mapOf(
            "km" to ("សួស្តីអ្នកទាំងអស់គ្នា។" to "តើថ្ងៃនេះអ្នកសុខសប្បាយជាទេ?"),
            "en" to ("Hello everyone." to "How are you today?"),
            "es" to ("Hola a todos." to "¿Cómo están hoy?"),
            "fr" to ("Bonjour à tous." to "Comment allez-vous aujourd'hui ?"),
            "de" to ("Hallo zusammen." to "Wie geht es euch heute?"),
            "zh" to ("大家好。" to "你们今天好吗？"),
            "ja" to ("皆さん、こんにちは。" to "今日はお元気ですか？"),
            "ko" to ("안녕하세요, 여러분." to "오늘 어떻게 지내세요?"),
            "vi" to ("Xin chào mọi người." to "Hôm nay các bạn thế nào?"),
            "th" to ("สวัสดีทุกคน" to "วันนี้เป็นอย่างไรบ้าง"),
        )

        /** Falls back to the language's own name, spoken in that language. */
        fun voiceSample(target: TargetLanguage): String =
            SAMPLES[target.tag] ?: target.locale.getDisplayLanguage(target.locale)
    }
}
