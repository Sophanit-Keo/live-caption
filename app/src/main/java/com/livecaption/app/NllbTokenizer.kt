package com.livecaption.app

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.text.Normalizer

/**
 * Pure-Kotlin reader for the NLLB-200 `tokenizer.json` (SentencePiece BPE in
 * Hugging Face format): text ⇄ token ids for the offline translator.
 */
class NllbTokenizer(
    private val vocab: Map<String, Int>,
    /** "left right" → merge priority (lower merges first). */
    private val merges: Map<String, Int>,
    /** Special tokens and language codes such as `khm_Khmr`. */
    private val specials: Map<String, Int>,
) {
    private val pieces: Array<String?> = arrayOfNulls<String>((vocab.values.maxOrNull() ?: 0) + 1).also {
        for ((piece, id) in vocab) it[id] = piece
    }
    private val specialIds = specials.values.toSet()
    private val unk = specials["<unk>"] ?: 3
    private val cache = HashMap<String, IntArray>()

    val eos: Int = specials["</s>"] ?: 2

    fun languageId(code: String): Int = specials[code] ?: throw IllegalArgumentException("Unknown language $code")

    /** Token ids for [text], without language or end-of-sentence markers. */
    fun encode(text: String): IntArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = ArrayList<Int>()
        for (word in normalized.split(WHITESPACE)) {
            if (word.isEmpty()) continue
            for (id in encodeWord(WORD_START + word)) out.add(id)
        }
        return out.toIntArray()
    }

    private fun encodeWord(word: String): IntArray {
        cache[word]?.let { return it }
        // Start from single characters and repeatedly apply the best-ranked merge.
        val parts = ArrayList<String>()
        var i = 0
        while (i < word.length) {
            val n = Character.charCount(word.codePointAt(i))
            parts.add(word.substring(i, i + n))
            i += n
        }
        while (parts.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (j in 0 until parts.size - 1) {
                val rank = merges[parts[j] + " " + parts[j + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    best = j
                }
            }
            if (best < 0) break
            parts[best] = parts[best] + parts[best + 1]
            parts.removeAt(best + 1)
        }
        val ids = ArrayList<Int>(parts.size)
        for (part in parts) {
            val id = vocab[part] ?: unk
            // Consecutive unknown pieces collapse into one <unk>.
            if (id == unk && ids.lastOrNull() == unk) continue
            ids.add(id)
        }
        return ids.toIntArray().also { if (cache.size < 20_000) cache[word] = it }
    }

    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in specialIds) continue
            sb.append(pieces.getOrNull(id) ?: continue)
        }
        return sb.toString().replace(WORD_START, " ").trim()
    }

    companion object {
        private const val WORD_START = "▁"
        private val WHITESPACE = Regex("\\s+")

        /** Stream-parses `tokenizer.json` (17 MB) without building a full JSON tree. */
        fun load(file: File): NllbTokenizer {
            val vocab = HashMap<String, Int>(400_000)
            val merges = HashMap<String, Int>(400_000)
            val specials = HashMap<String, Int>()
            JsonReader(file.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "added_tokens" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                var id = -1
                                var content = ""
                                r.beginObject()
                                while (r.hasNext()) {
                                    when (r.nextName()) {
                                        "id" -> id = r.nextInt()
                                        "content" -> content = r.nextString()
                                        else -> r.skipValue()
                                    }
                                }
                                r.endObject()
                                specials[content] = id
                            }
                            r.endArray()
                        }
                        "model" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                when (r.nextName()) {
                                    "vocab" -> {
                                        r.beginObject()
                                        while (r.hasNext()) vocab[r.nextName()] = r.nextInt()
                                        r.endObject()
                                    }
                                    "merges" -> {
                                        r.beginArray()
                                        while (r.hasNext()) {
                                            // Either "left right" or ["left", "right"], depending on the exporter.
                                            val key = if (r.peek() == JsonToken.BEGIN_ARRAY) {
                                                r.beginArray()
                                                val pair = r.nextString() + " " + r.nextString()
                                                r.endArray()
                                                pair
                                            } else {
                                                r.nextString()
                                            }
                                            merges[key] = merges.size
                                        }
                                        r.endArray()
                                    }
                                    else -> r.skipValue()
                                }
                            }
                            r.endObject()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            return NllbTokenizer(vocab, merges, specials)
        }
    }
}
