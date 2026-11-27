package com.livecaption.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.nl.translate.TranslateLanguage
import com.livecaption.app.databinding.ActivityTranscriptsBinding
import com.livecaption.app.databinding.ItemTranscriptBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Saved transcripts, newest first. */
class TranscriptsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptsBinding
    private lateinit var store: TranscriptStore
    private val adapter = SessionAdapter { startActivity(TranscriptActivity.intent(this, it.info.id)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityTranscriptsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)
        store = TranscriptStore(TranscriptActivity.storeDir(this))

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.transcripts)
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.deleteAll) confirmDeleteAll()
            true
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        // Reload when shown, and as the running session gets new lines.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptionState.transcriptVersion.collect { load() }
            }
        }
    }

    private suspend fun load() {
        val sessions = withContext(Dispatchers.IO) { store.list() }
        adapter.submitList(sessions)
        binding.empty.isVisible = sessions.isEmpty()
    }

    private fun confirmDeleteAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_all_title)
            .setMessage(R.string.delete_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.deleteAll(keep = CaptionState.currentTranscript.value) }
                    load()
                }
            }
            .show()
    }

    private class SessionAdapter(private val onClick: (Transcript) -> Unit) :
        ListAdapter<Transcript, SessionAdapter.Holder>(DIFF) {

        class Holder(val binding: ItemTranscriptBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTranscriptBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val t = getItem(position)
            val context = holder.binding.root.context
            with(holder.binding) {
                title.text = t.title().ifEmpty { context.getString(R.string.transcript_untitled) }
                meta.text = TranscriptLabels.meta(context, t)
                preview.text = TranscriptLabels.preview(t)
                preview.isVisible = preview.text.isNotEmpty()
                val live = CaptionState.currentTranscript.value == t.info.id
                badge.isVisible = live || t.summary != null
                badge.setText(if (live) R.string.transcript_recording else R.string.summary_title)
                root.setOnClickListener { onClick(t) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<Transcript>() {
                override fun areItemsTheSame(a: Transcript, b: Transcript) = a.info.id == b.info.id
                override fun areContentsTheSame(a: Transcript, b: Transcript) = a == b
            }
        }
    }
}

/** Shared wording for transcripts in lists, headers and exports. */
object TranscriptLabels {

    fun date(t: Transcript): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.info.startedAt))

    /** "English → Khmer", "Detected automatically → Khmer", or just "English". */
    fun languages(context: Context, info: TranscriptInfo): String {
        val locale = context.resources.configuration.locales[0]
        val source = if (info.sourceTag == TranscriptInfo.AUTO) context.getString(R.string.language_auto)
        else Locale.forLanguageTag(info.sourceTag).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
        val target = targetLanguage(info)?.displayName(locale) ?: return source
        return "$source → $target"
    }

    fun targetLanguage(info: TranscriptInfo): TargetLanguage? =
        info.targetTag.takeIf { it.isNotEmpty() }?.let { tag ->
            TargetLanguage.catalog(TranslateLanguage.getAllLanguages()).firstOrNull { it.tag == tag }
        }

    fun meta(context: Context, t: Transcript): String =
        listOf(date(t), TranscriptFormat.formatDuration(t.durationMs), languages(context, t.info))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /** The summary's overview, or else what was said after the first line (which is the title). */
    fun preview(t: Transcript): String {
        val overview = t.summary?.body?.split(Regex("\n\\s*\n"))?.firstOrNull()?.trim()
        if (!overview.isNullOrEmpty()) return overview
        return t.lines.drop(1).take(3).joinToString(" ") { it.translation ?: it.original }
    }

    fun formatLabels(context: Context, t: Transcript) = TranscriptFormat.Labels(
        heading = context.getString(R.string.export_heading),
        summary = context.getString(R.string.summary_title),
        transcript = context.getString(R.string.transcript_lines_heading),
        languages = languages(context, t.info),
        topic = context.getString(R.string.export_topic),
        speaker = { speaker(context, it) },
    )

    /** "Speaker 2 · Woman", "Woman" or "Speaker 2"; with [withMarker], "👩 " in front. */
    fun speaker(context: Context, line: TranscriptLine, withMarker: Boolean = false): String? {
        val voice = when (line.voice) {
            VoiceType.MAN -> context.getString(R.string.voice_man)
            VoiceType.WOMAN -> context.getString(R.string.voice_woman)
            VoiceType.CHILD -> context.getString(R.string.voice_child)
            VoiceType.UNKNOWN -> null
        }
        val number = line.speaker?.let { context.getString(R.string.speaker_number, it) }
        val text = listOfNotNull(number, voice).joinToString(" · ").ifEmpty { return null }
        val marker = when (line.voice) {
            VoiceType.MAN -> "\uD83D\uDC68 "
            VoiceType.WOMAN -> "\uD83D\uDC69 "
            VoiceType.CHILD -> "\uD83E\uDDD2 "
            VoiceType.UNKNOWN -> ""
        }
        return if (withMarker) marker + text else text
    }
}
