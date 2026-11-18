package com.livecaption.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPipelineTest {

    /** Translates "x" to "T(x)" after [latencyMs]; scripted failures are thrown first, in order. */
    private class FakeTranslator(
        override val partialIntervalMs: Long? = null,
        override val minPartialWords: Int = 1,
        private val latencyMs: Long = 0,
    ) : TextTranslator {
        val calls = mutableListOf<Pair<String, List<TranslatedLine>>>()
        val failures = ArrayDeque<Exception>()

        override suspend fun prepare(onStatus: (String) -> Unit) = Unit

        override suspend fun translate(text: String, context: List<TranslatedLine>): String {
            calls += text to context
            delay(latencyMs)
            failures.removeFirstOrNull()?.let { throw it }
            return "T($text)"
        }

        override fun close() = Unit
    }

    private class Recorder : TranslationPipeline.Listener {
        val partials = mutableListOf<String>()
        val finals = mutableListOf<TranslatedLine>()
        val rateLimits = mutableListOf<Long>()
        override fun onPartialTranslation(text: String) {
            partials += text
        }

        override fun onFinalTranslation(line: TranslatedLine) {
            finals += line
        }

        override fun onRateLimited(retryAfterMs: Long) {
            rateLimits += retryAfterMs
        }
    }

    private fun TestScope.pipeline(translator: TextTranslator, recorder: Recorder) =
        TranslationPipeline(backgroundScope, translator, { testScheduler.currentTime }, recorder)

    @Test
    fun finishedSentencesCarryTheLastThreeAsContext() = runTest {
        val translator = FakeTranslator()
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        for (s in listOf("a", "b", "c", "d", "e")) {
            p.submitFinal(s)
            runCurrent()
        }
        assertEquals(listOf("T(a)", "T(b)", "T(c)", "T(d)", "T(e)"), recorder.finals.map { it.translation })
        assertEquals(emptyList<TranslatedLine>(), translator.calls[0].second)
        assertEquals(listOf("b", "c", "d"), translator.calls[4].second.map { it.source })
    }

    @Test
    fun sentencesThatArriveDuringATranslationAreMergedNotDropped() = runTest {
        val translator = FakeTranslator(latencyMs = 1_000)
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        p.submitFinal("one.")
        runCurrent()
        p.submitFinal("two.")
        p.submitFinal("three.")
        advanceTimeBy(5_000)
        assertEquals(listOf("one.", "two. three."), translator.calls.map { it.first })
        assertEquals(listOf("T(one.)", "T(two. three.)"), recorder.finals.map { it.translation })
    }

    @Test
    fun rateLimitPausesThenRetriesWithEverythingQueued() = runTest {
        val translator = FakeTranslator()
        translator.failures += RateLimitedException(5_000, "slow down")
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        p.submitFinal("first.")
        runCurrent()
        assertEquals(listOf(5_000L), recorder.rateLimits)
        p.submitFinal("second.")
        advanceTimeBy(4_000)
        assertEquals(1, translator.calls.size) // Still waiting out the limit.
        advanceTimeBy(1_500)
        assertEquals("first. second.", translator.calls.last().first)
        assertEquals(listOf("T(first. second.)"), recorder.finals.map { it.translation })
    }

    @Test
    fun otherErrorsDropTheSentenceAndMoveOn() = runTest {
        val translator = FakeTranslator()
        translator.failures += IllegalStateException("boom")
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        p.submitFinal("lost.")
        runCurrent()
        p.submitFinal("kept.")
        runCurrent()
        assertEquals(listOf("T(kept.)"), recorder.finals.map { it.translation })
    }

    @Test
    fun sentenceInProgressIsThrottledAndNeedsEnoughWords() = runTest {
        val translator = FakeTranslator(partialIntervalMs = 5_000, minPartialWords = 3)
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        p.submitPartial("one two")
        runCurrent()
        assertEquals(0, translator.calls.size)
        p.submitPartial("one two three")
        runCurrent()
        assertEquals(listOf("one two three"), translator.calls.map { it.first })
        advanceTimeBy(1_000)
        p.submitPartial("one two three four")
        advanceTimeBy(3_000)
        assertEquals(1, translator.calls.size)
        advanceTimeBy(1_500)
        assertEquals("one two three four", translator.calls.last().first)
        assertEquals(listOf("T(one two three)", "T(one two three four)"), recorder.partials)
    }

    @Test
    fun lateTranslationOfAnUnfinishedSentenceIsDiscarded() = runTest {
        val translator = FakeTranslator(partialIntervalMs = 0, latencyMs = 1_000)
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        p.submitPartial("hello wor")
        runCurrent()
        p.submitFinal("Hello world.")
        advanceTimeBy(3_000)
        assertEquals(emptyList<String>(), recorder.partials)
        assertEquals(listOf("T(Hello world.)"), recorder.finals.map { it.translation })
    }

    @Test
    fun engineWithoutPartialsOnlyTranslatesFinishedSentences() = runTest {
        val translator = FakeTranslator(partialIntervalMs = null)
        val recorder = Recorder()
        val p = pipeline(translator, recorder)
        p.submitPartial("some words here")
        advanceTimeBy(10_000)
        assertEquals(0, translator.calls.size)
    }
}
