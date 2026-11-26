package com.livecaption.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * Offline translation with Meta's NLLB-200 (distilled 600M, 8-bit quantized)
 * running on ONNX Runtime. Not thread-safe: call [translate] from one thread at a time.
 *
 * [beams] > 1 decodes with beam search: it keeps that many candidate translations
 * and returns the most likely complete one, which is more accurate than always
 * taking the single most likely next word (greedy, [beams] = 1).
 */
class NllbTranslator(
    modelDir: File,
    sourceLanguage: String,
    targetLanguage: String,
    threads: Int = 4,
    private val beams: Int = 1,
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val tokenizer = NllbTokenizer.load(File(modelDir, TOKENIZER_FILE))
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val sourceId = tokenizer.languageId(sourceLanguage)
    private val targetId = tokenizer.languageId(targetLanguage)

    /** Decoder cache inputs, e.g. `past_key_values.0.decoder.key`. */
    private val pastNames: List<String>

    init {
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            encoder = env.createSession(File(modelDir, ENCODER_FILE).path, options)
            decoder = env.createSession(File(modelDir, DECODER_FILE).path, options)
        }
        pastNames = decoder.inputNames.filter { it.startsWith(PAST_PREFIX) }
    }

    fun translate(text: String): String {
        val words = tokenizer.encode(text)
        if (words.isEmpty()) return ""
        val input = longArrayOf(sourceId.toLong()) +
            words.take(MAX_INPUT_TOKENS).map { it.toLong() } + tokenizer.eos.toLong()
        val maxNewTokens = minOf(MAX_OUTPUT_TOKENS, words.size * 3 + 10)
        val output = if (beams > 1) beamSearch(input, maxNewTokens, beams) else greedy(input, maxNewTokens)
        return tokenizer.decode(output)
    }

    private fun greedy(input: LongArray, maxNewTokens: Int): List<Int> {
        val inputIds = longTensor(input)
        val mask = longTensor(LongArray(input.size) { 1 })
        val encoded = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to mask))
        // The first decoder step computes the encoder attention cache, which every later step reuses.
        var first: OrtSession.Result? = null
        var previous: OrtSession.Result? = null
        val emptyPast = pastNames.associateWith { emptyPast(it) }
        val output = ArrayList<Int>()
        try {
            val hidden = encoded.get(0) as OnnxTensor
            var next = longArrayOf(tokenizer.eos.toLong(), targetId.toLong())
            while (output.size < maxNewTokens) {
                val feeds = HashMap<String, OnnxTensor>()
                val step = longTensor(next)
                val useCache = OnnxTensor.createTensor(env, booleanArrayOf(first != null))
                feeds["input_ids"] = step
                feeds["encoder_attention_mask"] = mask
                feeds["encoder_hidden_states"] = hidden
                feeds["use_cache_branch"] = useCache
                for (name in pastNames) {
                    val present = name.replace(PAST_PREFIX, PRESENT_PREFIX)
                    feeds[name] = when {
                        first == null -> emptyPast.getValue(name)
                        ".encoder." in name -> first.get(present).get() as OnnxTensor
                        else -> previous!!.get(present).get() as OnnxTensor
                    }
                }
                val result = try {
                    decoder.run(feeds)
                } finally {
                    step.close()
                    useCache.close()
                }
                if (previous != null && previous !== first) previous.close()
                previous = result
                if (first == null) first = result

                val token = argmaxLast(result.get("logits").get() as OnnxTensor)
                if (token == tokenizer.eos) break
                output.add(token)
                if (isLooping(output)) break
                next = longArrayOf(token.toLong())
            }
        } finally {
            if (previous != null && previous !== first) previous.close()
            first?.close()
            emptyPast.values.forEach { it.close() }
            encoded.close()
            inputIds.close()
            mask.close()
        }
        return output
    }

    /** One candidate translation: its tokens so far and their total log-probability. */
    private class Hypothesis(val tokens: List<Int>, val score: Double, val parent: Int)

    /**
     * Beam search over [width] candidates, run as one batch so each step reads the
     * model weights once for all of them. Candidates that finish are scored by their
     * average log-probability per token, so short endings are not unfairly favoured.
     */
    private fun beamSearch(input: LongArray, maxNewTokens: Int, width: Int): List<Int> {
        val sourceLength = input.size.toLong()
        val inputIds = longTensor(input)
        val sourceMask = longTensor(LongArray(input.size) { 1 })
        val encoded = encoder.run(mapOf("input_ids" to inputIds, "attention_mask" to sourceMask))
        val batch = width.toLong()
        // Every candidate reads the same source sentence.
        val hidden = repeatRows(encoded.get(0) as OnnxTensor, width)
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(width * input.size) { 1 }), longArrayOf(batch, sourceLength))
        val emptyPast = pastNames.associateWith { emptyPast(it, batch) }

        var alive = List(width) { i -> Hypothesis(emptyList(), if (i == 0) 0.0 else Double.NEGATIVE_INFINITY, 0) }
        val finished = ArrayList<Pair<List<Int>, Double>>()
        // The first decoder step computes the encoder attention cache, which every later step reuses.
        var first: OrtSession.Result? = null
        var previous: OrtSession.Result? = null
        var selfCache: Map<String, OnnxTensor> = emptyMap()
        try {
            var next = LongArray(width * 2) { if (it % 2 == 0) tokenizer.eos.toLong() else targetId.toLong() }
            var stepLength = 2L
            while (alive.first().tokens.size < maxNewTokens) {
                val step = OnnxTensor.createTensor(env, LongBuffer.wrap(next), longArrayOf(batch, stepLength))
                val useCache = OnnxTensor.createTensor(env, booleanArrayOf(first != null))
                val feeds = HashMap<String, OnnxTensor>()
                feeds["input_ids"] = step
                feeds["encoder_attention_mask"] = mask
                feeds["encoder_hidden_states"] = hidden
                feeds["use_cache_branch"] = useCache
                for (name in pastNames) {
                    feeds[name] = when {
                        first == null -> emptyPast.getValue(name)
                        ".encoder." in name -> first.get(name.replace(PAST_PREFIX, PRESENT_PREFIX)).get() as OnnxTensor
                        else -> selfCache.getValue(name)
                    }
                }
                val result = try {
                    decoder.run(feeds)
                } finally {
                    step.close()
                    useCache.close()
                    selfCache.values.forEach { it.close() }
                    selfCache = emptyMap()
                }
                if (previous != null && previous !== first) previous.close()
                previous = result
                if (first == null) first = result

                alive = advance(alive, result.get("logits").get() as OnnxTensor, width, finished)
                if (finished.size >= width || alive.all { it.score == Double.NEGATIVE_INFINITY }) break
                selfCache = reorderSelfCache(result, alive.map { it.parent })
                next = LongArray(width) { alive[it].tokens.last().toLong() }
                stepLength = 1L
            }
        } finally {
            selfCache.values.forEach { it.close() }
            if (previous != null && previous !== first) previous.close()
            first?.close()
            emptyPast.values.forEach { it.close() }
            hidden.close()
            mask.close()
            encoded.close()
            inputIds.close()
            sourceMask.close()
        }
        val best = finished.maxByOrNull { it.second }?.first
        return best ?: alive.filter { it.score > Double.NEGATIVE_INFINITY }
            .maxByOrNull { it.score / it.tokens.size.coerceAtLeast(1) }?.tokens.orEmpty()
    }

    /** Extends every candidate by one token and keeps the [width] best; completed ones go to [finished]. */
    private fun advance(
        alive: List<Hypothesis>,
        logits: OnnxTensor,
        width: Int,
        finished: MutableList<Pair<List<Int>, Double>>,
    ): List<Hypothesis> {
        val shape = logits.info.shape
        val positions = shape[1].toInt()
        val vocab = shape[2].toInt()
        val buffer = logits.floatBuffer
        val perRow = width * 2
        // (candidate row, token, total log-probability), best first.
        val candidates = ArrayList<Triple<Int, Int, Double>>(width * perRow)
        val values = FloatArray(vocab)
        for (row in alive.indices) {
            if (alive[row].score == Double.NEGATIVE_INFINITY) continue
            buffer.position((row * positions + positions - 1) * vocab)
            buffer.get(values)
            val logZ = logSumExp(values)
            for (token in topTokens(values, perRow)) {
                candidates.add(Triple(row, token, alive[row].score + values[token] - logZ))
            }
        }
        candidates.sortByDescending { it.third }

        val next = ArrayList<Hypothesis>(width)
        for ((rank, candidate) in candidates.withIndex()) {
            val (row, token, score) = candidate
            val tokens = alive[row].tokens
            if (token == tokenizer.eos) {
                // Like Hugging Face's beam search, only an ending that ranks among the top beams counts.
                if (rank < width && tokens.isNotEmpty()) finished.add(tokens to score / (tokens.size + 1))
                continue
            }
            val extended = tokens + token
            if (isLooping(extended)) continue
            next.add(Hypothesis(extended, score, row))
            if (next.size == width) break
        }
        // Keep the batch size fixed; padding rows never win.
        while (next.size < width) {
            val padding = next.firstOrNull() ?: Hypothesis(alive[0].tokens + tokenizer.eos, Double.NEGATIVE_INFINITY, 0)
            next.add(Hypothesis(padding.tokens, Double.NEGATIVE_INFINITY, padding.parent))
        }
        return next
    }

    /** New self-attention caches whose row i is the cache of candidate [parents] i came from. */
    private fun reorderSelfCache(result: OrtSession.Result, parents: List<Int>): Map<String, OnnxTensor> {
        val reordered = HashMap<String, OnnxTensor>()
        try {
            for (name in pastNames) {
                if (".encoder." in name) continue
                val present = result.get(name.replace(PAST_PREFIX, PRESENT_PREFIX)).get() as OnnxTensor
                val shape = present.info.shape
                val source = present.floatBuffer
                val rowSize = (source.capacity() / shape[0]).toInt()
                val target = ByteBuffer.allocateDirect(source.capacity() * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val row = FloatArray(rowSize)
                for (parent in parents) {
                    source.position(parent * rowSize)
                    source.get(row)
                    target.put(row)
                }
                target.rewind()
                reordered[name] = OnnxTensor.createTensor(env, target, shape)
            }
        } catch (e: Exception) {
            reordered.values.forEach { it.close() }
            throw e
        }
        return reordered
    }

    /** [count] copies of a [1, ...] tensor stacked into a batch. */
    private fun repeatRows(tensor: OnnxTensor, count: Int): OnnxTensor {
        val source = tensor.floatBuffer
        val target = ByteBuffer.allocateDirect(source.capacity() * count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(count) {
            source.rewind()
            target.put(source)
        }
        target.rewind()
        val shape = tensor.info.shape.copyOf().also { it[0] = count.toLong() }
        return OnnxTensor.createTensor(env, target, shape)
    }

    private fun longTensor(values: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))

    /** Zero-length cache tensor for the first decoder step: [batch, heads, 0, headSize]. */
    private fun emptyPast(name: String, batch: Long = 1): OnnxTensor {
        val shape = (decoder.inputInfo.getValue(name).info as TensorInfo).shape
        val heads = shape.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_HEADS
        val headSize = shape.getOrNull(3)?.takeIf { it > 0 } ?: DEFAULT_HEAD_SIZE
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(batch, heads, 0, headSize))
    }

    /** Index of the highest score at the last sequence position of a [1, seq, vocab] tensor. */
    private fun argmaxLast(logits: OnnxTensor): Int {
        val shape = logits.info.shape
        val vocab = shape[2].toInt()
        val buffer = logits.floatBuffer
        val offset = (shape[1].toInt() - 1) * vocab
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in 0 until vocab) {
            val score = buffer.get(offset + i)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Decoding can get stuck repeating itself; detect a repeated 4-token run. */
    private fun isLooping(tokens: List<Int>): Boolean {
        val n = tokens.size
        return n >= 8 && tokens.subList(n - 4, n) == tokens.subList(n - 8, n - 4)
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        const val TOKENIZER_FILE = "tokenizer.json"
        const val ENCODER_FILE = "encoder_model_quantized.onnx"
        const val DECODER_FILE = "decoder_model_merged_quantized.onnx"

        private const val PAST_PREFIX = "past_key_values"
        private const val PRESENT_PREFIX = "present"
        private const val MAX_INPUT_TOKENS = 120
        private const val MAX_OUTPUT_TOKENS = 160
        private const val DEFAULT_HEADS = 16L
        private const val DEFAULT_HEAD_SIZE = 64L

        /** ML Kit / ISO language tag → NLLB language code, for every speech language the app offers. */
        val LANGUAGE_CODES = mapOf(
            "ar" to "arb_Arab", "ca" to "cat_Latn", "cs" to "ces_Latn", "de" to "deu_Latn",
            "en" to "eng_Latn", "eo" to "epo_Latn", "es" to "spa_Latn", "fa" to "pes_Arab",
            "fr" to "fra_Latn", "gu" to "guj_Gujr", "hi" to "hin_Deva", "it" to "ita_Latn",
            "ja" to "jpn_Jpan", "ka" to "kat_Geor", "km" to "khm_Khmr", "ko" to "kor_Hang",
            "nl" to "nld_Latn", "pl" to "pol_Latn", "pt" to "por_Latn", "ru" to "rus_Cyrl",
            "te" to "tel_Telu", "tr" to "tur_Latn", "uk" to "ukr_Cyrl", "vi" to "vie_Latn",
            "zh" to "zho_Hans",
        )
    }
}

/** log(sum(exp(values))), computed without overflow. */
internal fun logSumExp(values: FloatArray): Double {
    var max = Float.NEGATIVE_INFINITY
    for (v in values) if (v > max) max = v
    var sum = 0.0
    for (v in values) sum += exp((v - max).toDouble())
    return max + ln(sum)
}

/** Indices of the [count] largest [values], largest first. */
internal fun topTokens(values: FloatArray, count: Int): IntArray {
    val best = IntArray(count) { -1 }
    val bestValues = FloatArray(count) { Float.NEGATIVE_INFINITY }
    for (i in values.indices) {
        val v = values[i]
        if (v <= bestValues[count - 1]) continue
        var j = count - 1
        while (j > 0 && bestValues[j - 1] < v) {
            bestValues[j] = bestValues[j - 1]
            best[j] = best[j - 1]
            j--
        }
        bestValues[j] = v
        best[j] = i
    }
    return best.filter { it >= 0 }.toIntArray()
}
