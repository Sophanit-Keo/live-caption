package com.livecaption.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class OfflineKhmerTest {

    @Test
    fun khmerWordsAreJoinedWithZeroWidthSpaces() {
        val zwsp = "​"
        assertEquals("ឥឡូវនេះ${zwsp}យើង${zwsp}មាន${zwsp}កណ្ដុរ។ គាត់${zwsp}បាន", OfflineKhmerTranslator.joinKhmerWords("ឥឡូវនេះ យើង មាន កណ្ដុរ។ គាត់ បាន"))
        // Spaces next to Latin text and digits stay.
        assertEquals("លោក Dr Ehud Ur ជា", OfflineKhmerTranslator.joinKhmerWords("លោក Dr Ehud Ur ជា"))
        assertEquals("អាយុ 4 ខែ", OfflineKhmerTranslator.joinKhmerWords("អាយុ 4 ខែ"))
    }

    @Test
    fun picksTheLargestScoresInOrder() {
        assertArrayEquals(intArrayOf(3, 0, 2), topTokens(floatArrayOf(5f, 1f, 4f, 9f, -2f), 3))
        assertArrayEquals(intArrayOf(1, 0), topTokens(floatArrayOf(1f, 2f), 4))
    }

    @Test
    fun logSumExpIsStableForLargeScores() {
        assertEquals(ln(exp(1.0) + exp(2.0)), logSumExp(floatArrayOf(1f, 2f)), 1e-6)
        assertEquals(1000.0 + ln(2.0), logSumExp(floatArrayOf(1000f, 1000f)), 1e-4)
    }
}
