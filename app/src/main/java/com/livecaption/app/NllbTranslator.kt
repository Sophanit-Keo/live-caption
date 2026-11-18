package com.livecaption.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Offline translation with Meta's NLLB-200 (distilled 600M, 8-bit quantized)
 * running on ONNX Runtime. Not thread-safe: call [translate] from one thread at a time.
 */
class NllbTranslator(modelDir: File, sourceLanguage: String, targetLanguage: String, threads: Int = 4) : AutoCloseable {

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
                val cut = loopCut(output)
                if (cut != null) {
                    while (output.size > cut) output.removeAt(output.size - 1)
                    break
                }
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
        return tokenizer.decode(output)
    }

    private fun longTensor(values: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1, values.size.toLong()))

    /** Zero-length cache tensor for the first decoder step: [batch, heads, 0, headSize]. */
    private fun emptyPast(name: String): OnnxTensor {
        val shape = (decoder.inputInfo.getValue(name).info as TensorInfo).shape
        val heads = shape.getOrNull(1)?.takeIf { it > 0 } ?: DEFAULT_HEADS
        val headSize = shape.getOrNull(3)?.takeIf { it > 0 } ?: DEFAULT_HEAD_SIZE
        return OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, heads, 0, headSize))
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

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        /**
         * Greedy decoding can get stuck repeating itself ("… the the the …"). If the end of
         * [tokens] is such a loop, returns the length to cut back to (keeping one copy); else null.
         * Words that legitimately appear twice, or a single doubled word, are left alone.
         */
        fun loopCut(tokens: List<Int>): Int? {
            val n = tokens.size
            for (period in 1..MAX_LOOP_PERIOD) {
                val copies = if (period < 3) 3 else 2
                if (n < period * copies) continue
                val looping = (1 until copies).all { r ->
                    (0 until period).all { k -> tokens[n - 1 - k] == tokens[n - 1 - k - r * period] }
                }
                if (looping) return n - (copies - 1) * period
            }
            return null
        }

        const val TOKENIZER_FILE = "tokenizer.json"
        const val ENCODER_FILE = "encoder_model_quantized.onnx"
        const val DECODER_FILE = "decoder_model_merged_quantized.onnx"

        private const val PAST_PREFIX = "past_key_values"
        private const val PRESENT_PREFIX = "present"
        private const val MAX_INPUT_TOKENS = 120
        private const val MAX_OUTPUT_TOKENS = 160
        private const val DEFAULT_HEADS = 16L
        private const val DEFAULT_HEAD_SIZE = 64L
        private const val MAX_LOOP_PERIOD = 10

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
