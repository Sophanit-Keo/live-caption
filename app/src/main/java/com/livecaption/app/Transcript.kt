package com.livecaption.app

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One finished caption line; times are milliseconds since the session started. */
data class TranscriptLine(
    val startMs: Long,
    val endMs: Long,
    val original: String,
    val translation: String?,
    /** Speaker number when speakers were told apart. */
    val speaker: Int? = null,
    val voice: VoiceType = VoiceType.UNKNOWN,
)

/** What was captioned, and how. */
data class TranscriptInfo(
    val id: String,
    val startedAt: Long,
    val engine: TranslationEngine,
    /** Spoken language tag ("en"), or [AUTO] when Gemini Live detects it. */
    val sourceTag: String,
    /** Translation target tag, or empty for captions only. */
    val targetTag: String,
    val topic: String = "",
) {
    companion object {
        const val AUTO = "auto"
    }
}

data class TranscriptSummary(val createdAt: Long, val language: String, val text: String) {
    /** The first line of the summary, which the model is asked to make a short title. */
    val title: String get() = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** Everything after the title (the screen already shows the title as its heading). */
    val body: String
        get() {
            val lines = text.trim().lines()
            return if (lines.size <= 1) text.trim() else lines.drop(1).joinToString("\n").trim()
        }
}

data class Transcript(
    val info: TranscriptInfo,
    val lines: List<TranscriptLine>,
    val summary: TranscriptSummary?,
    val endedAt: Long?,
) {
    /** Session length: until "end" was recorded, or else until the last line. */
    val durationMs: Long get() = endedAt?.let { it - info.startedAt } ?: (lines.lastOrNull()?.endMs ?: 0L)

    /** Summary title, or else the start of the first line. */
    fun title(maxChars: Int = 60): String {
        summary?.title?.takeIf { it.isNotEmpty() }?.let { return it }
        val first = lines.firstOrNull()?.let { it.translation ?: it.original }.orEmpty()
        return if (first.length <= maxChars) first else first.take(maxChars).trimEnd() + "…"
    }
}

/**
 * Saved transcripts: one JSON-lines file per session in [dir], appended as captions arrive
 * (so nothing is lost if the app is killed). Records: one "session" header, then "line"
 * records, optional "summary" records (the newest wins) and an "end" record.
 */
class TranscriptStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun create(info: TranscriptInfo): TranscriptWriter {
        val writer = TranscriptWriter(fileFor(info.id))
        writer.write(
            JSONObject()
                .put("type", "session")
                .put("id", info.id)
                .put("startedAt", info.startedAt)
                .put("engine", info.engine.name)
                .put("source", info.sourceTag)
                .put("target", info.targetTag)
                .put("topic", info.topic),
        )
        return writer
    }

    /** All sessions, newest first. Files that can't be read are skipped. */
    fun list(): List<Transcript> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray())
            .mapNotNull { load(it.name.removeSuffix(EXTENSION)) }
            .sortedByDescending { it.info.startedAt }

    fun load(id: String): Transcript? {
        val file = fileFor(id)
        if (!file.isFile) return null
        var info: TranscriptInfo? = null
        val lines = ArrayList<TranscriptLine>()
        var summary: TranscriptSummary? = null
        var endedAt: Long? = null
        file.forEachLine(Charsets.UTF_8) { raw ->
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                return@forEachLine // A line cut short when the app was killed.
            }
            when (json.optString("type")) {
                "session" -> info = TranscriptInfo(
                    id = json.optString("id", id),
                    startedAt = json.optLong("startedAt"),
                    engine = TranslationEngine.fromName(json.optString("engine")),
                    sourceTag = json.optString("source"),
                    targetTag = json.optString("target"),
                    topic = json.optString("topic"),
                )
                "line" -> lines += TranscriptLine(
                    startMs = json.optLong("start"),
                    endMs = json.optLong("end"),
                    original = json.optString("original"),
                    translation = json.optString("translation").takeIf { json.has("translation") && it.isNotEmpty() },
                    speaker = if (json.has("speaker")) json.optInt("speaker") else null,
                    voice = VoiceType.fromName(json.optString("voice")),
                )
                "summary" -> summary = TranscriptSummary(json.optLong("createdAt"), json.optString("language"), json.optString("text"))
                "end" -> endedAt = json.optLong("endedAt")
            }
        }
        return info?.let { Transcript(it, lines, summary, endedAt) }
    }

    fun saveSummary(id: String, summary: TranscriptSummary) {
        TranscriptWriter(fileFor(id)).use {
            it.write(
                JSONObject()
                    .put("type", "summary")
                    .put("createdAt", summary.createdAt)
                    .put("language", summary.language)
                    .put("text", summary.text),
            )
        }
    }

    fun delete(id: String): Boolean = fileFor(id).delete()

    /** Deletes every transcript except [keep] (the one being recorded). */
    fun deleteAll(keep: String? = null) {
        dir.listFiles { f -> f.name.endsWith(EXTENSION) && f.name != "$keep$EXTENSION" }?.forEach { it.delete() }
    }

    /** A new id for a session starting at [now] that no existing transcript uses. */
    fun newId(now: Long): String {
        val base = idFor(now)
        var id = base
        var n = 2
        while (File(dir, id + EXTENSION).exists()) id = "$base-${n++}"
        return id
    }

    private fun fileFor(id: String): File {
        require(id.matches(ID_PATTERN)) { "Bad transcript id: $id" }
        return File(dir, id + EXTENSION)
    }

    companion object {
        private const val EXTENSION = ".jsonl"
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]+")

        /** A readable, sortable id like "20261120-140312". */
        fun idFor(now: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
    }
}

/** Appends records to a transcript file. Each record is flushed at once. */
class TranscriptWriter(file: File) : AutoCloseable {
    private val out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))

    /** Lines written by this writer. */
    var lineCount = 0
        private set

    @Synchronized
    fun write(record: JSONObject) {
        out.write(record.toString())
        out.newLine()
        out.flush()
    }

    fun append(line: TranscriptLine) {
        val json = JSONObject()
            .put("type", "line")
            .put("start", line.startMs)
            .put("end", line.endMs)
            .put("original", line.original)
        line.translation?.let { json.put("translation", it) }
        line.speaker?.let { json.put("speaker", it) }
        if (line.voice != VoiceType.UNKNOWN) json.put("voice", line.voice.name)
        write(json)
        lineCount++
    }

    fun finish(endedAt: Long) {
        write(JSONObject().put("type", "end").put("endedAt", endedAt))
        close()
    }

    @Synchronized
    override fun close() = out.close()
}

/** Plain-text and subtitle (.srt) versions of a transcript, for sharing and saving. */
object TranscriptFormat {

    /** Headings and names for [toText], in the user's language. */
    data class Labels(
        val heading: String,
        val summary: String,
        val transcript: String,
        val languages: String,
        val topic: String,
        /** "Speaker 2 · Woman", or null when nothing is known about who spoke. */
        val speaker: (TranscriptLine) -> String? = { null },
    )

    /** The label to show before [line], only when the speaker differs from [previous]. */
    fun speakerChange(line: TranscriptLine, previous: TranscriptLine?, label: (TranscriptLine) -> String?): String? {
        if (line.speaker == null && line.voice == VoiceType.UNKNOWN) return null
        if (previous != null && previous.speaker == line.speaker && previous.voice == line.voice) return null
        return label(line)
    }

    /** English speaker labels, for text sent to Gemini: "Speaker 2 (woman)". */
    fun englishSpeaker(line: TranscriptLine): String? {
        val voice = when (line.voice) {
            VoiceType.MAN -> "man"
            VoiceType.WOMAN -> "woman"
            VoiceType.CHILD -> "child"
            VoiceType.UNKNOWN -> null
        }
        return when {
            line.speaker != null && voice != null -> "Speaker ${line.speaker} ($voice)"
            line.speaker != null -> "Speaker ${line.speaker}"
            voice != null -> voice.replaceFirstChar { it.uppercase() }
            else -> null
        }
    }

    fun toText(t: Transcript, labels: Labels, date: String): String = buildString {
        append(labels.heading).append('\n')
        append(date).append(" · ").append(formatDuration(t.durationMs))
        if (labels.languages.isNotEmpty()) append(" · ").append(labels.languages)
        append('\n')
        if (t.info.topic.isNotBlank()) append(labels.topic).append(": ").append(t.info.topic.trim()).append('\n')
        t.summary?.let {
            append('\n').append(labels.summary).append('\n').append(it.text.trim()).append('\n')
        }
        append('\n').append(labels.transcript).append('\n')
        t.lines.forEachIndexed { i, line ->
            append('[').append(formatClock(line.startMs)).append("] ")
            speakerChange(line, t.lines.getOrNull(i - 1), labels.speaker)?.let { append(it).append(": ") }
            val main = line.translation ?: line.original
            append(main).append('\n')
            if (line.translation != null && line.original.isNotBlank()) {
                append("        ").append(line.original).append('\n')
            }
        }
    }

    /**
     * SubRip subtitles: translation on the first line, original below. Times count from when
     * captions were started.
     */
    fun toSrt(t: Transcript, speaker: (TranscriptLine) -> String? = { null }): String = buildString {
        t.lines.forEachIndexed { i, line ->
            val end = maxOf(line.endMs, line.startMs + MIN_CUE_MS)
            append(i + 1).append('\n')
            append(srtTime(line.startMs)).append(" --> ").append(srtTime(end)).append('\n')
            speakerChange(line, t.lines.getOrNull(i - 1), speaker)?.let { append('[').append(it).append("] ") }
            line.translation?.let { append(it).append('\n') }
            if (line.original.isNotBlank()) append(line.original).append('\n')
            append('\n')
        }
    }

    /** "4:05" or "1:02:03". */
    fun formatClock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    /** "45 s", "23 min", "1 h 05 min". */
    fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "%d h %02d min".format(Locale.US, s / 3600, s / 60 % 60)
        }
    }

    private fun srtTime(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(Locale.US, t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000)
    }

    private const val MIN_CUE_MS = 1_000L
}
