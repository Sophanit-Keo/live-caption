package com.livecaption.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.nl.translate.TranslateLanguage
import com.livecaption.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    /** Translation targets: display name → ML Kit tag ("" = no translation). */
    private val targets: List<Pair<String, String>> by lazy {
        listOf(getString(R.string.no_translation) to "") +
            TranslateLanguage.getAllLanguages()
                .map { Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { c -> c.titlecase() } to it }
                .sortedBy { it.first }
    }

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

    private fun bindSettings() = with(binding) {
        sourceLanguage.adapter = dropdown(SpeechLanguage.ALL)
        sourceLanguage.setSelection(SpeechLanguage.ALL.indexOf(SpeechLanguage.byModelName(prefs.sourceModel)))
        sourceLanguage.onSelected { prefs.sourceModel = SpeechLanguage.ALL[it].modelName }

        targetLanguage.adapter = dropdown(targets.map { it.first })
        targetLanguage.setSelection(targets.indexOfFirst { it.second == prefs.targetTag }.coerceAtLeast(0))
        targetLanguage.onSelected { prefs.targetTag = targets[it].second }

        audioSource.check(if (prefs.useMicrophone) R.id.sourceMic else R.id.sourceDevice)
        audioSourceHint.setText(if (prefs.useMicrophone) R.string.source_mic_hint else R.string.source_device_hint)
        audioSource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.useMicrophone = checkedId == R.id.sourceMic
            audioSourceHint.setText(if (prefs.useMicrophone) R.string.source_mic_hint else R.string.source_device_hint)
        }

        showOriginal.isChecked = prefs.showOriginal
        showOriginal.setOnCheckedChangeListener { _, checked -> prefs.showOriginal = checked }

        textSize.value = prefs.textSizeSp.coerceIn(textSize.valueFrom, textSize.valueTo)
        textSize.addOnChangeListener { _, value, _ -> prefs.textSizeSp = value }
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
        listOf(sourceLanguage, targetLanguage, sourceDevice, sourceMic, showOriginal, textSize)
            .forEach { it.isEnabled = editable }
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
}
