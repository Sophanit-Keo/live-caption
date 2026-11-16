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

interface TextTranslator {
    /**
     * Minimum pause between translations of the unfinished sentence, or null to
     * translate finished sentences only.
     */
    val partialIntervalMs: Long?

    /** Gets ready to translate (downloads a model, or checks the online service); throws on failure. */
    suspend fun prepare(onStatus: (String) -> Unit)

    suspend fun translate(text: String): String

    fun close()
}

/** Translation targets beyond ML Kit's offline languages. */
object KhmerTargets {
    const val OFFLINE = "km-offline"
    const val GEMINI = "km-gemini"

    fun isKhmer(tag: String) = tag == OFFLINE || tag == GEMINI
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

    override suspend fun translate(text: String): String = client.translate(text).await()

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
            NllbTranslator(dir, source, NllbTranslator.LANGUAGE_CODES.getValue("km"))
        }
    }

    override suspend fun translate(text: String): String = lock.withLock {
        withContext(Dispatchers.Default) { nllb?.translate(text).orEmpty() }
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

/** Online translation with the Gemini API, using the user's own API key. */
class GeminiTranslator(
    private val context: Context?,
    private val apiKey: String,
    private val sourceName: String,
    private val targetName: String,
    private val endpoint: String = ENDPOINT,
) : TextTranslator {

    // Free-tier keys allow only a few requests per minute; finished sentences get priority.
    override val partialIntervalMs: Long? = 6_000L

    override suspend fun prepare(onStatus: (String) -> Unit) {
        context?.let { onStatus(it.getString(R.string.status_connecting_translation)) }
        translate("hello")
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val instruction = "You translate live speech captions from $sourceName into $targetName. " +
            "The input is raw speech recognition output and may lack punctuation or contain errors. " +
            "Reply with only the natural $targetName translation, nothing else."
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            )
            .put("generationConfig", JSONObject().put("temperature", 0))
            .toString()
            .toByteArray()
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IOException(parseError(response) ?: "Gemini error (HTTP $code)")
            parseTranslation(response)
        } finally {
            conn.disconnect()
        }
    }

    override fun close() = Unit

    companion object {
        /** An alias Google keeps pointed at its current fast model. */
        const val MODEL = "gemini-flash-latest"
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        fun parseTranslation(response: String): String {
            val parts = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Gemini returned no translation")
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
    }
}
