package com.livecaption.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSpeechTest {

    @Test
    fun playbackSpeedStepsUpGraduallyAndDownWithAMargin() {
        var step = 0
        fun at(backlogMs: Long): Float {
            step = PlaybackPacing.step(backlogMs, step)
            return PlaybackPacing.SPEEDS[step]
        }
        assertEquals(1.0f, at(500))
        assertEquals(1.1f, at(2_000))
        assertEquals(1.25f, at(4_000))
        assertEquals(1.4f, at(9_000))
        // Coming down needs a clear margin below each threshold, so the speed doesn't flutter.
        assertEquals(1.4f, at(5_600))
        assertEquals(1.25f, at(5_200))
        assertEquals(1.25f, at(3_000))
        assertEquals(1.1f, at(2_700))
        assertEquals(1.1f, at(900))
        assertEquals(1.0f, at(700))
        // A burst jumps straight to the right speed.
        assertEquals(1.4f, at(20_000))
    }

    @Test
    fun pendingWordsAreMarkedForDimming() {
        var now = 0L
        val buffer = SentenceBuffer { now }
        buffer.commit("Hello there.")
        buffer.setPending("How are")
        val parts = buffer.snapshot().continuous()
        assertEquals("Hello there. How are", parts.text)
        assertEquals("How are", parts.text.substring(parts.pendingStart))
        assertEquals("How are", buffer.snapshot().newest().text)
        assertEquals(0, buffer.snapshot().newest().pendingStart)

        buffer.commit("How are you?")
        val done = buffer.snapshot().continuous()
        assertEquals(done.text.length, done.pendingStart)

        val rolling = SentenceBuffer { now }
        rolling.append("Bonjour")
        rolling.setPending("tout le")
        val r = rolling.snapshot().continuous()
        assertEquals("Bonjour tout le", r.text)
        assertEquals("tout le", r.text.substring(r.pendingStart))
        rolling.append(" tout le monde")
        val after = rolling.snapshot()
        assertEquals("Bonjour tout le monde", after.current)
        assertEquals(after.current.length, after.pendingStart)
    }

    @Test
    fun scheduledCaptionsKeepTheirOrder() = runTest {
        val shown = mutableListOf<String>()
        val scheduler = CaptionScheduler(backgroundScope) { testScheduler.currentTime }
        scheduler.post(2_000) { shown += "first" }
        scheduler.post(500) { shown += "second" } // Shorter delay, but must not jump ahead.
        scheduler.post(0) { shown += "third" }
        runCurrent()
        assertEquals(emptyList<String>(), shown)
        advanceTimeBy(2_001)
        assertEquals(listOf("first", "second", "third"), shown)
        scheduler.post(0) { shown += "now" } // Nothing pending: runs at once.
        assertEquals("now", shown.last())
    }

    @Test
    fun speechQueueCountsSentencesAlreadyInTheEngine() {
        val q = SpeechQueue<String>(maxPending = 2) { it.isBlank() }
        assertEquals(1f, q.rateFactor())
        assertEquals(1.15f, q.rateFactor(alsoQueued = 1), 0.001f)
        q.add("a")
        assertEquals(1.3f, q.rateFactor(alsoQueued = 1), 0.001f)
        assertEquals(1.3f, q.rateFactor(alsoQueued = 5), 0.001f) // capped
    }

    @Test
    fun topicGuidesBothGeminiEngines() {
        val km = TargetLanguage("km", null, "km")
        val gemini = GeminiTranslator("k", "English (US)", km, topic = " a football match ")
        val body = JSONObject(gemini.buildRequest(GeminiTranslator.MODELS[0], "goal", emptyList()))
        val instruction = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction, instruction.contains("The speech comes from: a football match."))

        assertNull(LiveTranslator.instructionFor("  ", "Khmer"))
        val liveInstruction = LiveTranslator.instructionFor("a cooking show", "Khmer")!!
        val session = LiveTranslateSession(OkHttpClient(), "ws://localhost/", "km", NoopListener, liveInstruction)
        val setup = JSONObject(session.setupMessage()).getJSONObject("setup")
        val text = setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text, text.startsWith("The audio is from: a cooking show."))
        assertTrue(text, text.contains("natural spoken Khmer"))

        val plain = JSONObject(LiveTranslateSession(OkHttpClient(), "ws://localhost/", "km", NoopListener).setupMessage())
        assertFalse(plain.getJSONObject("setup").has("systemInstruction"))
    }

    @Test
    fun styleCyclesThroughAllChoices() {
        assertEquals(SubtitleStyle.OUTLINE, SubtitleStyle.BOX.next())
        assertEquals(SubtitleStyle.CONTRAST, SubtitleStyle.OUTLINE.next())
        assertEquals(SubtitleStyle.BOX, SubtitleStyle.CONTRAST.next())
        assertEquals(SubtitleStyle.BOX, SubtitleStyle.fromName("unknown"))
    }

    private object NoopListener : LiveTranslateSession.Listener {
        override fun onReady(session: LiveTranslateSession) = Unit
        override fun onInputTranscript(session: LiveTranslateSession, text: String, interim: Boolean) = Unit
        override fun onOutputTranscript(session: LiveTranslateSession, text: String) = Unit
        override fun onAudio(session: LiveTranslateSession, pcm: ByteArray, sampleRate: Int) = Unit
        override fun onTurnComplete(session: LiveTranslateSession) = Unit
        override fun onInterrupted(session: LiveTranslateSession) = Unit
        override fun onGoAway(session: LiveTranslateSession, timeLeftMs: Long) = Unit
        override fun onClosed(session: LiveTranslateSession, error: String?, wasReady: Boolean) = Unit
    }
}
