package com.livecaption.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real NLLB model. Skipped unless NLLB_DIR points at a directory with
 * tokenizer.json and the two .onnx files (about 910 MB, not kept in the repo).
 */
class NllbModelTest {

    private val dir = File(System.getProperty("nllb.dir").orEmpty())

    @Test
    fun tokenizerMatchesReferenceIds() {
        assumeTrue(File(dir, NllbTranslator.TOKENIZER_FILE).isFile)
        val tokenizer = NllbTokenizer.load(File(dir, NllbTranslator.TOKENIZER_FILE))
        // Expected ids come from the Hugging Face `tokenizers` library on the same file.
        File(dir, "reference.tsv").takeIf { it.isFile }?.forEachLine { line ->
            val (text, ids) = line.split('\t')
            assertArrayEquals(text, ids.split(' ').map { it.toInt() }.toIntArray(), tokenizer.encode(text))
        }
        assertTrue(tokenizer.languageId("khm_Khmr") > 0)
    }

    @Test
    fun translatesEnglishIntoKhmerScript() {
        assumeTrue(File(dir, NllbTranslator.DECODER_FILE).isFile)
        NllbTranslator(dir, "eng_Latn", "khm_Khmr").use { translator ->
            for (text in listOf("hello how are you today", "the weather is very nice and i want to go to the market")) {
                val start = System.nanoTime()
                val result = translator.translate(text)
                println("NLLB [${(System.nanoTime() - start) / 1_000_000} ms] $text -> $result")
                assertTrue(result, result.count { it in 'ក'..'៿' } > result.length / 2)
            }
        }
    }
}
