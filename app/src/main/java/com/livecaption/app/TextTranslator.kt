package com.livecaption.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

interface TextTranslator {
    /** Minimum pause between translations of the unfinished sentence. */
    val partialIntervalMs: Long

    /** Gets ready to translate (downloads a model, or checks the online service); throws on failure. */
    suspend fun prepare()

    suspend fun translate(text: String): String

    fun close()
}

/** Target languages that have no offline model and go through the online service instead. */
object OnlineLanguages {
    val ALL = mapOf("km" to "Khmer")

    fun isOnline(tag: String) = tag in ALL
}

/** Offline translation with ML Kit. */
class MlKitTranslator(sourceTag: String, targetTag: String) : TextTranslator {
    private val client = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(sourceTag).setTargetLanguage(targetTag).build(),
    )

    override val partialIntervalMs = 0L

    override suspend fun prepare() {
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    override suspend fun translate(text: String): String = client.translate(text).await()

    override fun close() = client.close()
}

/** Online translation with the Google Cloud Translation API (v2), using the user's own API key. */
class CloudTranslator(
    private val apiKey: String,
    private val sourceTag: String,
    private val targetTag: String,
    private val endpoint: String = ENDPOINT,
) : TextTranslator {

    // Every request is billed per character, so re-translate the growing sentence sparingly.
    override val partialIntervalMs = 1_200L

    override suspend fun prepare() {
        translate("hello")
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("q", JSONArray().put(text))
            .put("source", sourceTag)
            .put("target", targetTag)
            .put("format", "text")
            .toString()
            .toByteArray()
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) throw IOException(parseError(response) ?: "Translation service error (HTTP $code)")
            parseTranslation(response)
        } finally {
            conn.disconnect()
        }
    }

    override fun close() = Unit

    companion object {
        const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"

        fun parseTranslation(response: String): String =
            JSONObject(response).getJSONObject("data").getJSONArray("translations")
                .getJSONObject(0).getString("translatedText")

        fun parseError(response: String): String? =
            try {
                JSONObject(response).getJSONObject("error").getString("message").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
    }
}
