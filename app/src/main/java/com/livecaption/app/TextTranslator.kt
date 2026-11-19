package com.livecaption.app

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A finished caption and its translation, kept as context for the next ones. */
data class TranslatedLine(val source: String, val translation: String)

/** The service asked us to slow down; try again after [retryAfterMs]. */
class RateLimitedException(val retryAfterMs: Long, message: String) : IOException(message)

interface TextTranslator {
    /**
     * Minimum pause between translations of the unfinished sentence, or null to
     * translate finished sentences only.
     */
    val partialIntervalMs: Long?

    /** Unfinished sentences shorter than this are not translated (saves requests). */
    val minPartialWords: Int get() = 1

    /** Gets ready to translate (downloads a model, or checks the online service); throws on failure. */
    suspend fun prepare(onStatus: (String) -> Unit)

    /** [context] holds the most recent finished lines, oldest first; engines may ignore it. */
    suspend fun translate(text: String, context: List<TranslatedLine> = emptyList()): String

    fun close()
}

/** Offline translation with ML Kit. */
class MlKitTranslator(private val context: Context, sourceTag: String, targetTag: String) : TextTranslator {
    private val client = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(sourceTag).setTargetLanguage(targetTag).build(),
    )

    override val partialIntervalMs = 0L

    override suspend fun prepare(onStatus: (String) -> Unit) {
        onStatus(context.getString(R.string.status_downloading_translation))
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(text: String, context: List<TranslatedLine>): String =
        client.translate(text).await()

    override fun close() = client.close()
}

/** Offline Khmer translation with NLLB-200; downloads the model (about 910 MB) on first use. */
class OfflineKhmerTranslator(private val context: Context, private val sourceTag: String) : TextTranslator {
    private var nllb: NllbTranslator? = null
    private val lock = Mutex()

    // A sentence takes seconds on a phone, so only finished sentences are translated.
    override val partialIntervalMs: Long? = null

    override suspend fun prepare(onStatus: (String) -> Unit) {
        val source = NllbTranslator.LANGUAGE_CODES[sourceTag]
            ?: throw IllegalArgumentException("Offline Khmer does not support this spoken language")
        val dir = File(context.filesDir, "nllb").apply { mkdirs() }
        val total = FILES.sumOf { it.second }
        var before = 0L
        for ((name, size) in FILES) {
            val base = before
            ModelManager.downloadFile("$BASE_URL/$name", File(dir, name.substringAfterLast('/'))) { done ->
                onStatus(context.getString(R.string.status_downloading_khmer, ((base + done) * 100 / total).toInt()))
            }
            before += size
        }
        onStatus(context.getString(R.string.status_loading_khmer))
        nllb = withContext(Dispatchers.Default) {
            NllbTranslator(dir, source, NllbTranslator.LANGUAGE_CODES.getValue(TargetLanguage.KHMER))
        }
    }

    override suspend fun translate(text: String, context: List<TranslatedLine>): String = lock.withLock {
        withContext(Dispatchers.Default) { TextTidy.khmer(nllb?.translate(text).orEmpty()) }
    }

    override fun close() {
        nllb?.close()
        nllb = null
    }

    private companion object {
        const val BASE_URL = "https://huggingface.co/Xenova/nllb-200-distilled-600M/resolve/main"

        /** File name (relative to [BASE_URL]) → size in bytes, used for overall progress. */
        val FILES = listOf(
            NllbTranslator.TOKENIZER_FILE to 17_331_224L,
            "onnx/${NllbTranslator.ENCODER_FILE}" to 419_120_483L,
            "onnx/${NllbTranslator.DECODER_FILE}" to 475_505_771L,
        )
    }
}

/** Error reply from the Gemini API. */
class GeminiException(val code: Int, message: String) : IOException(message)

/**
 * Online translation with the Gemini API, using the user's own API key. Sends the last few
 * finished captions as context so names, pronouns and terms stay consistent.
 */
class GeminiTranslator(
    private val apiKey: String,
    private val sourceName: String,
    private val target: TargetLanguage,
    private val baseUrl: String = BASE_URL,
    private val connectingMessage: String = "",
    /** Optional description of what is being watched, to help with terms and names. */
    private val topic: String = "",
) : TextTranslator {

    /** A model to try, with the thinking level that keeps it fast (null = model default). */
    data class ModelChoice(val id: String, val thinkingLevel: String?)

    private var model = MODELS.first()

    // Free keys allow only a limited number of requests per minute: finished sentences come
    // first, and the sentence in progress is refreshed only when it is long.
    override val partialIntervalMs: Long? = 5_000L
    override val minPartialWords = 6

    val modelId: String get() = model.id

    /** Picks the first model this key can use; a wrong key fails here, before captions start. */
    override suspend fun prepare(onStatus: (String) -> Unit) {
        if (connectingMessage.isNotEmpty()) onStatus(connectingMessage)
        var lastError: Exception? = null
        for (candidate in MODELS) {
            var choice = candidate
            while (true) {
                try {
                    request(choice, "Hello", emptyList())
                    model = choice
                    return
                } catch (e: RateLimitedException) {
                    model = choice // The key works; it is only out of quota for the moment.
                    return
                } catch (e: GeminiException) {
                    lastError = e
                    when {
                        // Model not available to this key or region: try the next one.
                        e.code == 404 -> break
                        // Older models reject thinking levels: retry with the model's default.
                        e.code == 400 && choice.thinkingLevel != null && e.message.orEmpty().contains("thinking", true) ->
                            choice = choice.copy(thinkingLevel = null)
                        else -> throw e
                    }
                }
            }
        }
        throw lastError ?: IOException("No Gemini model available")
    }

    override suspend fun translate(text: String, context: List<TranslatedLine>): String = request(model, text, context)

    private suspend fun request(choice: ModelChoice, text: String, context: List<TranslatedLine>): String =
        withContext(Dispatchers.IO) {
            val body = buildRequest(choice, text, context).toByteArray()
            val conn = URL("$baseUrl/v1beta/models/${choice.id}:generateContent").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 20_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                when {
                    code in 200..299 -> parseTranslation(response)
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

    internal fun buildRequest(choice: ModelChoice, text: String, context: List<TranslatedLine>): String {
        val targetName = target.englishName
        val instruction = buildString {
            append("You are a professional interpreter writing live subtitles. ")
            append("Translate speech from $sourceName into $targetName. ")
            append("The captions come from automatic speech recognition, so they may lack punctuation, ")
            append("break sentences in odd places, or contain misheard words: infer what the speaker most ")
            append("likely meant, using the earlier captions as context. ")
            append("Reply with only the $targetName translation of the caption after \"Translate this caption:\" - ")
            append("natural, fluent, everyday $targetName with correct punctuation, and no quotes, notes or alternatives. ")
            append("Keep names, numbers and brands accurate. ")
            append("If the caption is an unfinished sentence, translate it as an unfinished sentence.")
            if (topic.isNotBlank()) append(" The speech comes from: ${topic.trim()}.")
            if (target.tag == TargetLanguage.KHMER) {
                append(" Write in Khmer script. Do not put spaces between Khmer words; use spaces only ")
                append("where Khmer writing normally separates phrases.")
            }
        }
        val userText = buildString {
            if (context.isNotEmpty()) {
                append("Earlier captions, for context only (do not translate them again):\n")
                for (line in context) append(line.source).append(" => ").append(line.translation).append('\n')
                append('\n')
            }
            append("Translate this caption:\n").append(text)
        }
        val generationConfig = JSONObject()
        choice.thinkingLevel?.let { generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", it)) }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userText))),
                ),
            )
            .put("generationConfig", generationConfig)
            .toString()
    }

    override fun close() = Unit

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com"
        private const val DEFAULT_RETRY_MS = 10_000L

        /**
         * Tried in order. 3.5 Flash-Lite is Google's low-latency model for translation and thinks
         * minimally by default; the Flash alias is a fallback for keys without access to it.
         */
        val MODELS = listOf(
            ModelChoice("gemini-3.5-flash-lite", null),
            ModelChoice("gemini-flash-latest", "low"),
        )

        fun parseTranslation(response: String): String {
            val json = JSONObject(response)
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException(
                    json.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { it.isNotEmpty() }?.let { "Gemini blocked this caption ($it)" }
                        ?: "Gemini returned no translation",
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
}
