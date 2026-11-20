package com.livecaption.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Error reply from the Gemini API. */
class GeminiException(val code: Int, message: String) : IOException(message)

/** The Gemini text API (generateContent), shared by translation and summaries. */
object Gemini {
    const val BASE_URL = "https://generativelanguage.googleapis.com"
    private const val DEFAULT_RETRY_MS = 10_000L

    /** A model to use, with the thinking level that keeps it fast enough (null = model default). */
    data class Model(val id: String, val thinkingLevel: String?)

    fun requestBody(model: Model, systemInstruction: String, userText: String): String {
        val generationConfig = JSONObject()
        model.thinkingLevel?.let { generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", it)) }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))),
                ),
            )
            .put("generationConfig", generationConfig)
            .toString()
    }

    /** Sends [body] to [model] and returns the reply text. Throws [RateLimitedException] or [GeminiException]. */
    suspend fun generate(baseUrl: String, apiKey: String, model: Model, body: String, readTimeoutMs: Int = 20_000): String =
        withContext(Dispatchers.IO) {
            val conn = URL("$baseUrl/v1beta/models/${model.id}:generateContent").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = readTimeoutMs
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                when {
                    code in 200..299 -> parseText(response)
                    code == 429 -> {
                        val retry = parseRetryDelayMs(response)
                            ?: conn.getHeaderField("Retry-After")?.toLongOrNull()?.times(1000)
                            ?: DEFAULT_RETRY_MS
                        throw RateLimitedException(retry, parseError(response) ?: "Gemini rate limit reached")
                    }
                    else -> throw GeminiException(code, parseError(response) ?: "Gemini error (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Runs [call] with the first of [models] this key can use: a model that is not available
     * (404) is skipped, and a model that rejects the thinking level is retried without it.
     */
    suspend fun <T> withFallback(models: List<Model>, call: suspend (Model) -> T): Pair<Model, T> {
        var lastError: Exception? = null
        for (candidate in models) {
            var model = candidate
            while (true) {
                try {
                    return model to call(model)
                } catch (e: GeminiException) {
                    lastError = e
                    when {
                        e.code == 404 -> break
                        e.code == 400 && model.thinkingLevel != null && e.message.orEmpty().contains("thinking", true) ->
                            model = model.copy(thinkingLevel = null)
                        else -> throw e
                    }
                }
            }
        }
        throw lastError ?: IOException("No Gemini model available")
    }

    fun parseText(response: String): String {
        val json = JSONObject(response)
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IOException(
                json.optJSONObject("promptFeedback")?.optString("blockReason")
                    ?.takeIf { it.isNotEmpty() }?.let { "Gemini blocked this text ($it)" }
                    ?: "Gemini returned no text",
            )
        return (0 until parts.length())
            .map { parts.getJSONObject(it) }
            .filterNot { it.optBoolean("thought") }
            .joinToString("") { it.optString("text") }
            .trim()
    }

    fun parseError(response: String): String? =
        try {
            JSONObject(response).getJSONObject("error").getString("message").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    /** Reads `google.rpc.RetryInfo.retryDelay` (for example "17s" or "1.5s") from an error reply. */
    fun parseRetryDelayMs(response: String): Long? =
        try {
            val details = JSONObject(response).getJSONObject("error").optJSONArray("details")
            (0 until (details?.length() ?: 0))
                .mapNotNull { details!!.optJSONObject(it)?.optString("retryDelay")?.takeIf { d -> d.endsWith("s") } }
                .firstNotNullOfOrNull { it.dropLast(1).toDoubleOrNull() }
                ?.let { (it * 1000).toLong() }
        } catch (e: Exception) {
            null
        }
}

/** Writes a summary of a transcript with Gemini. */
class Summarizer(private val apiKey: String, private val baseUrl: String = Gemini.BASE_URL) {

    /** [languageName] is the English name of the language to write in, e.g. "Khmer". */
    suspend fun summarize(transcript: Transcript, languageName: String, now: Long = System.currentTimeMillis()): TranscriptSummary {
        val body = { model: Gemini.Model -> Gemini.requestBody(model, instruction(languageName), userText(transcript)) }
        val (_, text) = Gemini.withFallback(MODELS) { model ->
            Gemini.generate(baseUrl, apiKey, model, body(model), readTimeoutMs = 120_000)
        }
        return TranscriptSummary(now, languageName, clean(text))
    }

    companion object {
        /** Quality matters more than speed here; Flash-Lite is the fallback. */
        val MODELS = listOf(Gemini.Model("gemini-flash-latest", "low"), Gemini.Model("gemini-3.5-flash-lite", null))

        /** Very long sessions keep their most recent part. */
        const val MAX_TRANSCRIPT_CHARS = 400_000

        fun instruction(languageName: String) = buildString {
            append("You summarize transcripts of speech captured as live captions, from videos, talks, lessons, ")
            append("meetings or conversations. The transcript was made by automatic speech recognition, so expect ")
            append("missing punctuation and misheard words; work out what was meant. ")
            append("Write everything in $languageName. ")
            append("Use exactly this layout, as plain text without Markdown (no # and no **):\n")
            append("1. A short title of at most 8 words, on the first line.\n")
            append("2. An empty line, then an overview of 2 to 4 sentences.\n")
            append("3. An empty line, then the heading \"Key points\" written in $languageName, then 3 to 8 lines ")
            append("that each start with \"• \".\n")
            append("4. Only if the speech contains decisions, tasks, deadlines or open questions: an empty line, ")
            append("the heading \"Action items\" written in $languageName, then lines that start with \"• \".\n")
            append("Speaker labels such as \"Speaker 2 (woman)\" come from automatic voice analysis and may be ")
            append("wrong; mention who said what only when it helps. ")
            append("Use only what is in the transcript. Do not invent facts, names or numbers.")
        }

        fun userText(t: Transcript): String {
            val lines = StringBuilder()
            t.lines.forEachIndexed { i, line ->
                val text = line.original.ifBlank { line.translation.orEmpty() }
                if (text.isBlank()) return@forEachIndexed
                lines.append('[').append(TranscriptFormat.formatClock(line.startMs)).append("] ")
                TranscriptFormat.speakerChange(line, t.lines.getOrNull(i - 1), TranscriptFormat::englishSpeaker)
                    ?.let { lines.append(it).append(": ") }
                lines.append(text).append('\n')
            }
            val transcript = if (lines.length > MAX_TRANSCRIPT_CHARS) lines.substring(lines.length - MAX_TRANSCRIPT_CHARS) else lines
            return buildString {
                if (t.info.topic.isNotBlank()) append("What was being watched: ").append(t.info.topic.trim()).append("\n\n")
                append("Transcript:\n").append(transcript)
            }
        }

        /** Removes Markdown the model may add anyway. */
        fun clean(text: String): String =
            text.lineSequence()
                .map { it.trimEnd().replace("**", "").replace(Regex("^#+\\s*"), "").replace(Regex("^[*-] "), "• ") }
                .joinToString("\n")
                .trim()
    }
}
