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
import java.io.File
import java.io.IOException

/**
 * A finished caption and its translation, kept as context for the next ones. [parts] is how
 * many finished sentences were merged into [source] (they pile up when translation is slow).
 */
data class TranslatedLine(val source: String, val translation: String, val parts: Int = 1)

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
            NllbTranslator(dir, source, NllbTranslator.LANGUAGE_CODES.getValue(TargetLanguage.KHMER), beams = NllbTranslator.BEAMS)
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

/**
 * Online translation with the Gemini API, using the user's own API key. Sends the last few
 * finished captions as context so names, pronouns and terms stay consistent.
 */
class GeminiTranslator(
    private val apiKey: String,
    private val sourceName: String,
    private val target: TargetLanguage,
    private val baseUrl: String = Gemini.BASE_URL,
    private val connectingMessage: String = "",
    /** Optional description of what is being watched, to help with terms and names. */
    private val topic: String = "",
) : TextTranslator {

    private var model = MODELS.first()

    // Free keys allow only a limited number of requests per minute: finished sentences come
    // first, and the sentence in progress is refreshed only when it is long.
    override val partialIntervalMs: Long? = 5_000L
    override val minPartialWords = 6

    val modelId: String get() = model.id

    /** Picks the first model this key can use; a wrong key fails here, before captions start. */
    override suspend fun prepare(onStatus: (String) -> Unit) {
        if (connectingMessage.isNotEmpty()) onStatus(connectingMessage)
        model = Gemini.withFallback(MODELS) { candidate ->
            try {
                request(candidate, "Hello", emptyList())
            } catch (e: RateLimitedException) {
                "" // The key works; it is only out of quota for the moment.
            }
        }.first
    }

    override suspend fun translate(text: String, context: List<TranslatedLine>): String = request(model, text, context)

    private suspend fun request(choice: Gemini.Model, text: String, context: List<TranslatedLine>): String =
        Gemini.generate(baseUrl, apiKey, choice, buildRequest(choice, text, context))

    internal fun buildRequest(choice: Gemini.Model, text: String, context: List<TranslatedLine>): String {
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
        return Gemini.requestBody(choice, instruction, userText)
    }

    override fun close() = Unit

    companion object {
        /**
         * Tried in order. 3.5 Flash-Lite is Google's low-latency model for translation and thinks
         * minimally by default; the Flash alias is a fallback for keys without access to it.
         */
        val MODELS = listOf(
            Gemini.Model("gemini-3.5-flash-lite", null),
            Gemini.Model("gemini-flash-latest", "low"),
        )
    }
}
