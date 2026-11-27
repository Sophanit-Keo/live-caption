package com.livecaption.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/** The scoring helpers behind NLLB beam search; the search itself runs in NllbModelTest. */
class BeamSearchTest {

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
