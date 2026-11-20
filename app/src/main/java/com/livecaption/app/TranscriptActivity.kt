package com.livecaption.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livecaption.app.databinding.ActivityTranscriptBinding
import com.livecaption.app.databinding.ItemTranscriptHeaderBinding
import com.livecaption.app.databinding.ItemTranscriptLineBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** One saved transcript: its summary, every line, and sharing/saving. */
class TranscriptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranscriptBinding
    private lateinit var store: TranscriptStore
    private lateinit var prefs: Prefs
    private lateinit var id: String
    private val adapter = LinesAdapter(::bindHeader)

    private var transcript: Transcript? = null
    private var summarizing = false
    private var summaryError: String? = null

    private val isLive get() = CaptionState.currentTranscript.value == id

    private val saveText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { export(it) { t -> TranscriptFormat.toText(t, TranscriptLabels.formatLabels(this, t), TranscriptLabels.date(t)) } }
    }
    private val saveSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri?.let { export(it) { t -> TranscriptFormat.toSrt(t) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(EXTRA_ID) ?: run {
            finish()
            return
        }
        prefs = Prefs(this)
        store = TranscriptStore(storeDir(this))
        binding = ActivityTranscriptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarPadding(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.share -> share()
                R.id.save -> chooseFileFormat()
                R.id.copy -> copy()
                R.id.delete -> confirmDelete()
            }
            true
        }
        binding.lines.layoutManager = LinearLayoutManager(this)
        binding.lines.adapter = adapter

        // While this session is still being recorded, new lines appear as they are said.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptionState.transcriptVersion.combine(CaptionState.currentTranscript) { v, _ -> v }.collect { load() }
            }
        }
    }

    private suspend fun load() {
        val t = withContext(Dispatchers.IO) { store.load(id) }
        if (t == null) {
            // A session that just started may not have its file yet; it appears with the first line.
            if (!isLive) finish()
            return
        }
        val wasAtEnd = !binding.lines.canScrollVertically(1)
        transcript = t
        binding.toolbar.menu.findItem(R.id.delete)?.isVisible = !isLive
        adapter.update(t.lines)
        if (isLive && wasAtEnd && t.lines.isNotEmpty()) binding.lines.scrollToPosition(adapter.itemCount - 1)
    }

    private fun bindHeader(h: ItemTranscriptHeaderBinding) {
        val t = transcript ?: return
        h.title.text = t.title().ifEmpty { getString(R.string.transcript_untitled) }
        h.meta.text = getString(
            R.string.transcript_meta,
            TranscriptLabels.meta(this, t),
            resources.getQuantityString(R.plurals.transcript_line_count, t.lines.size, t.lines.size),
        )
        h.recording.isVisible = isLive
        h.noLines.isVisible = t.lines.isEmpty()

        val language = summaryLanguage(t)
        h.summaryLanguage.text = getString(R.string.summary_in, language.label)
        h.summaryLanguage.setOnClickListener { chooseSummaryLanguage(it, t) }

        val summary = t.summary
        h.summaryText.isVisible = summary != null
        h.summaryText.text = summary?.body
        h.summaryProgress.isVisible = summarizing
        h.summarize.isEnabled = !summarizing && t.lines.isNotEmpty()
        h.summarize.setText(
            when {
                summary != null -> R.string.summarize_again
                isLive -> R.string.summarize_so_far
                else -> R.string.summarize
            },
        )
        h.summarize.setOnClickListener { summarize() }
        h.summaryHint.text = when {
            summarizing -> getString(R.string.summary_working)
            summaryError != null -> summaryError
            prefs.geminiApiKey.isBlank() && summary == null -> getString(R.string.summary_needs_key)
            summary == null -> getString(R.string.summary_hint)
            else -> ""
        }
        h.summaryHint.isVisible = h.summaryHint.text.isNotEmpty()
    }

    // region Summary

    private data class LanguageOption(val englishName: String, val label: String)

    /** Languages offered for the summary: the translation's, the spoken one's, the phone's, English. */
    private fun languageOptions(t: Transcript): List<LanguageOption> {
        val ui = resources.configuration.locales[0]
        val options = mutableListOf<LanguageOption>()
        TranscriptLabels.targetLanguage(t.info)?.let { options += LanguageOption(it.englishName, it.displayName(ui)) }
        val locales = buildList {
            if (t.info.sourceTag != TranscriptInfo.AUTO && t.info.sourceTag.isNotEmpty()) add(Locale.forLanguageTag(t.info.sourceTag))
            add(ui)
            add(Locale.ENGLISH)
        }
        for (l in locales) {
            options += LanguageOption(l.getDisplayLanguage(Locale.ENGLISH), l.getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) })
        }
        return options.filter { it.englishName.isNotEmpty() }.distinctBy { it.englishName }
    }

    private fun summaryLanguage(t: Transcript): LanguageOption {
        val options = languageOptions(t)
        return options.firstOrNull { it.englishName == prefs.summaryLanguage } ?: options.first()
    }

    private fun chooseSummaryLanguage(anchor: View, t: Transcript) {
        val options = languageOptions(t)
        val menu = PopupMenu(this, anchor)
        options.forEachIndexed { i, option -> menu.menu.add(0, i, i, option.label) }
        menu.setOnMenuItemClickListener { item ->
            prefs.summaryLanguage = options[item.itemId].englishName
            adapter.refreshHeader()
            true
        }
        menu.show()
    }

    private fun summarize() {
        val t = transcript ?: return
        if (prefs.geminiApiKey.isBlank()) {
            Toast.makeText(this, R.string.summary_needs_key, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            return
        }
        summarizing = true
        summaryError = null
        adapter.refreshHeader()
        val language = summaryLanguage(t).englishName
        lifecycleScope.launch {
            try {
                val summary = Summarizer(prefs.geminiApiKey).summarize(t, language)
                withContext(Dispatchers.IO) { store.saveSummary(id, summary) }
                summarizing = false
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimitedException) {
                summaryError = getString(R.string.summary_rate_limited, (e.retryAfterMs + 999) / 1000)
            } catch (e: Exception) {
                summaryError = getString(R.string.summary_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                summarizing = false
                adapter.refreshHeader()
            }
        }
    }

    // endregion

    // region Sharing and saving

    private fun plainText(t: Transcript) =
        TranscriptFormat.toText(t, TranscriptLabels.formatLabels(this, t), TranscriptLabels.date(t))

    private fun share() {
        val t = transcript ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, t.title().ifEmpty { getString(R.string.export_heading) })
            .putExtra(Intent.EXTRA_TEXT, plainText(t))
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun copy() {
        val t = transcript ?: return
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.export_heading), plainText(t)))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun chooseFileFormat() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_file)
            .setItems(arrayOf(getString(R.string.save_as_text), getString(R.string.save_as_srt))) { _, which ->
                val name = "LiveCaption-$id"
                if (which == 0) saveText.launch("$name.txt") else saveSrt.launch("$name.srt")
            }
            .show()
    }

    private fun export(uri: Uri, format: (Transcript) -> String) {
        val t = transcript ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(format(t).toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            Toast.makeText(this@TranscriptActivity, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_transcript_title)
            .setMessage(R.string.delete_transcript_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.delete(id) }
                    finish()
                }
            }
            .show()
    }

    // endregion

    /** The header (title, summary) followed by one row per line; new lines are inserted, not redrawn. */
    private class LinesAdapter(private val bindHeader: (ItemTranscriptHeaderBinding) -> Unit) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var lines: List<TranscriptLine> = emptyList()

        class Header(val binding: ItemTranscriptHeaderBinding) : RecyclerView.ViewHolder(binding.root)
        class Line(val binding: ItemTranscriptLineBinding) : RecyclerView.ViewHolder(binding.root)

        fun update(newLines: List<TranscriptLine>) {
            val old = lines
            lines = newLines
            if (newLines.size > old.size && newLines.subList(0, old.size) == old) {
                notifyItemChanged(0)
                notifyItemRangeInserted(old.size + 1, newLines.size - old.size)
            } else {
                @Suppress("NotifyDataSetChanged") // Rare: the transcript was reloaded or changed.
                notifyDataSetChanged()
            }
        }

        fun refreshHeader() = notifyItemChanged(0)

        override fun getItemCount() = lines.size + 1

        override fun getItemViewType(position: Int) = if (position == 0) HEADER else LINE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == HEADER) Header(ItemTranscriptHeaderBinding.inflate(inflater, parent, false))
            else Line(ItemTranscriptLineBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is Header -> bindHeader(holder.binding)
                is Line -> {
                    val line = lines[position - 1]
                    with(holder.binding) {
                        time.text = TranscriptFormat.formatClock(line.startMs)
                        main.text = line.translation ?: line.original
                        secondary.text = line.original
                        secondary.isVisible = line.translation != null && line.original.isNotBlank()
                    }
                }
            }
        }

        private companion object {
            const val HEADER = 0
            const val LINE = 1
        }
    }

    companion object {
        private const val EXTRA_ID = "transcriptId"

        fun intent(context: Context, id: String): Intent =
            Intent(context, TranscriptActivity::class.java).putExtra(EXTRA_ID, id)

        fun storeDir(context: Context) = File(context.filesDir, "transcripts")
    }
}

/** Pads [root] so content stays clear of the status and navigation bars (edge-to-edge). */
fun applySystemBarPadding(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
        insets
    }
}
