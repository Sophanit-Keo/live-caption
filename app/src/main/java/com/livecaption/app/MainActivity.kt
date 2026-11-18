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

        sourceLanguage.adapter = dropdown(SpeechLanguage.ALL)
        sourceLanguage.setSelection(SpeechLanguage.ALL.indexOf(SpeechLanguage.byModelName(prefs.sourceModel)))
        sourceLanguage.onSelected { prefs.sourceModel = SpeechLanguage.ALL[it].modelName }

        targetLanguage.onSelected {
            prefs.targetTag = targets.getOrNull(it)?.tag.orEmpty()
            updateHints()
            refreshVoice()
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
        testVoice.setOnClickListener { previewVoice() }

        showOriginal.isChecked = prefs.showOriginal
        showOriginal.setOnCheckedChangeListener { _, checked -> prefs.showOriginal = checked }

        textSize.value = prefs.textSizeSp.coerceIn(textSize.valueFrom, textSize.valueTo)
        textSize.addOnChangeListener { _, value, _ -> prefs.textSizeSp = value }

        onEngineChanged()
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
            when (val result = TtsVoice.find(this@MainActivity, target.voiceLocale)) {
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
        listOf(
            engineOffline, engineGemini, engineLive, apiKeyLayout, sourceLanguage, targetLanguage,
            sourceDevice, sourceMic, voiceEnabled, voiceRate, duckOthers, allowSpeaker, showOriginal, textSize,
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

        /** Falls back to the language's own name, spoken in that language. */
        fun voiceSample(target: TargetLanguage): String =
            SAMPLES[target.tag] ?: target.locale.getDisplayLanguage(target.locale)
    }
}
