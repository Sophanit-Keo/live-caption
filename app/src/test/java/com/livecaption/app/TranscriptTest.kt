package com.livecaption.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptTest {

    private val dir: File = Files.createTempDirectory("transcripts").toFile()
    private val store = TranscriptStore(dir)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun info(id: String, startedAt: Long = 1_000_000L, topic: String = "") =
        TranscriptInfo(id, startedAt, TranslationEngine.GEMINI, "en", "km", topic)

    @Test
    fun savesLinesSummaryAndEndAndReadsThemBack() {
        val writer = store.create(info("s1", topic = "a cooking show"))
        writer.append(TranscriptLine(1_500, 4_000, "Hello everyone.", "សួស្តីអ្នកទាំងអស់គ្នា។"))
        writer.append(TranscriptLine(5_000, 7_250, "Untranslated line.", null))
        writer.finish(1_000_000L + 65_000)
        store.saveSummary("s1", TranscriptSummary(2_000_000L, "Khmer", "ចម្អិនម្ហូប\n\nOverview.\n\nKey points\n• one"))

        val t = store.load("s1")!!
        assertEquals("a cooking show", t.info.topic)
        assertEquals(TranslationEngine.GEMINI, t.info.engine)
        assertEquals(2, t.lines.size)
        assertEquals("សួស្តីអ្នកទាំងអស់គ្នា។", t.lines[0].translation)
        assertNull(t.lines[1].translation)
        assertEquals(65_000L, t.durationMs)
        assertEquals("ចម្អិនម្ហូប", t.title())
        assertEquals("Overview.\n\nKey points\n• one", t.summary!!.body)
        assertEquals(2, writer.lineCount)
    }

    @Test
    fun aLineCutShortWhenTheAppWasKilledIsSkipped() {
        val writer = store.create(info("s2"))
        writer.append(TranscriptLine(0, 1_000, "Complete.", null))
        writer.close()
        File(dir, "s2.jsonl").appendText("{\"type\":\"line\",\"start\":2000,\"orig")
        val t = store.load("s2")!!
        assertEquals(listOf("Complete."), t.lines.map { it.original })
        assertNull(t.endedAt)
        assertEquals(1_000L, t.durationMs) // Until the last line.
        assertEquals("Complete.", t.title())
    }

    @Test
    fun listsNewestFirstAndDeletes() {
        store.create(info("older", startedAt = 1_000)).close()
        store.create(info("newer", startedAt = 2_000)).close()
        assertEquals(listOf("newer", "older"), store.list().map { it.info.id })
        store.deleteAll(keep = "newer")
        assertEquals(listOf("newer"), store.list().map { it.info.id })
        assertTrue(store.delete("newer"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun idsAreUniqueAndSafe() {
        val now = 1_763_640_000_000L
        val first = store.newId(now)
        store.create(info(first)).close()
        val second = store.newId(now)
        assertEquals("$first-2", second)
        assertThrows(IllegalArgumentException::class.java) { store.load("../escape") }
    }

    @Test
    fun textExportShowsSummaryTranslationAndOriginal() {
        val t = Transcript(
            info("s3", topic = "news"),
            listOf(TranscriptLine(65_000, 68_000, "Good evening.", "សាយ័ណ្ហសួស្តី។"), TranscriptLine(3_725_000, 3_726_000, "Bye.", null)),
            TranscriptSummary(0, "Khmer", "Title\n\nOverview."),
            endedAt = 1_000_000L + 3_730_000,
        )
        val labels = TranscriptFormat.Labels("Live Caption transcript", "Summary", "Transcript", "English → Khmer", "Topic")
        val text = TranscriptFormat.toText(t, labels, "20 Nov 2026, 14:03")
        assertTrue(text, text.startsWith("Live Caption transcript\n20 Nov 2026, 14:03 · 1 h 02 min · English → Khmer\nTopic: news\n"))
        assertTrue(text, text.contains("\nSummary\nTitle\n\nOverview.\n"))
        assertTrue(text, text.contains("[1:05] សាយ័ណ្ហសួស្តី។\n        Good evening.\n"))
        assertTrue(text, text.contains("[1:02:05] Bye.\n"))
    }

    @Test
    fun srtExportHasNumberedTimedCues() {
        val t = Transcript(
            info("s4"),
            listOf(TranscriptLine(1_500, 4_000, "Hello.", "សួស្តី។"), TranscriptLine(5_000, 5_200, "Short.", null)),
            null,
            null,
        )
        val srt = TranscriptFormat.toSrt(t)
        assertEquals(
            "1\n00:00:01,500 --> 00:00:04,000\nសួស្តី។\nHello.\n\n" +
                "2\n00:00:05,000 --> 00:00:06,000\nShort.\n\n",
            srt,
        )
    }

    @Test
    fun speakersAreSavedAndLabelledWhenTheyChange() {
        val writer = store.create(info("s6"))
        writer.append(TranscriptLine(0, 2_000, "Hi.", "សួស្តី។", speaker = 1, voice = VoiceType.WOMAN))
        writer.append(TranscriptLine(2_000, 4_000, "How are you?", null, speaker = 1, voice = VoiceType.WOMAN))
        writer.append(TranscriptLine(4_000, 6_000, "Fine.", null, speaker = 2, voice = VoiceType.MAN))
        writer.append(TranscriptLine(6_000, 7_000, "Yay!", null, voice = VoiceType.CHILD))
        writer.close()
        val t = store.load("s6")!!
        assertEquals(listOf(1, 1, 2, null), t.lines.map { it.speaker })
        assertEquals(VoiceType.CHILD, t.lines[3].voice)

        val labels = TranscriptFormat.Labels("H", "S", "T", "", "Topic") { TranscriptFormat.englishSpeaker(it) }
        val text = TranscriptFormat.toText(t, labels, "d")
        assertTrue(text, text.contains("[0:00] Speaker 1 (woman): សួស្តី។\n"))
        assertTrue(text, text.contains("[0:02] How are you?\n")) // same speaker: no label
        assertTrue(text, text.contains("[0:04] Speaker 2 (man): Fine.\n"))
        assertTrue(text, text.contains("[0:06] Child: Yay!\n"))
        val srt = TranscriptFormat.toSrt(t) { TranscriptFormat.englishSpeaker(it) }
        assertTrue(srt, srt.contains("[Speaker 2 (man)] Fine."))
        assertTrue(Summarizer.userText(t).contains("[0:04] Speaker 2 (man): Fine."))
    }

    @Test
    fun sentencesKeepTheirSpeakers() {
        val b = SentenceBuffer { 0L }
        val woman = SpeakerInfo(1, VoiceType.WOMAN)
        val man = SpeakerInfo(2, VoiceType.MAN)
        b.commit("Hello. How are you?", woman)
        b.setPending("I am", man)
        val s = b.snapshot()
        assertEquals(listOf(woman, woman), s.done.map { it.speaker })
        assertEquals(man, s.currentSpeaker)
        assertEquals(man, s.newestSpeaker())
        // Continuous text marks only where the speaker changes.
        val marked = s.continuous { if (it == man) "M: " else "W: " }.text
        assertEquals("W: Hello. How are you? M: I am", marked)
        // Streaming text keeps the speaker of the sentence being spoken.
        val live = SentenceBuffer { 0L }
        live.append("Bonjour. ", SpeakerInfo(null, VoiceType.CHILD))
        live.append("Salut", SpeakerInfo(null, VoiceType.CHILD))
        assertEquals(VoiceType.CHILD, live.snapshot().done.single().speaker?.voice)
    }

    @Test
    fun phoneVoicePitchFollowsTheSpeaker() {
        assertTrue(TtsVoice.pitchFor(VoiceType.MAN) < 1f)
        assertEquals(1f, TtsVoice.pitchFor(VoiceType.UNKNOWN))
        assertTrue(TtsVoice.pitchFor(VoiceType.CHILD) > TtsVoice.pitchFor(VoiceType.WOMAN))
        assertTrue(TtsVoice.pitchFor(VoiceType.WOMAN) > 1f)
    }

    @Test
    fun voiceprintsAreReadFromVoskResults() {
        val (vector, frames) = CaptionService.parseVoiceprint("""{"text":"hi","spk":[0.5,-1.25,2],"spk_frames":321}""")
        assertEquals(listOf(0.5f, -1.25f, 2f), vector!!.toList())
        assertEquals(321, frames)
        assertNull(CaptionService.parseVoiceprint("""{"text":"hi"}""").first)
    }

    @Test
    fun durationsAndClocksReadNaturally() {
        assertEquals("45 s", TranscriptFormat.formatDuration(45_000))
        assertEquals("23 min", TranscriptFormat.formatDuration(23 * 60_000L + 5_000))
        assertEquals("1 h 05 min", TranscriptFormat.formatDuration(65 * 60_000L))
        assertEquals("0:07", TranscriptFormat.formatClock(7_900))
        assertEquals("1:00:00", TranscriptFormat.formatClock(3_600_000))
    }

    @Test
    fun summarizerSendsTheTranscriptAndFallsBackToAnotherModel() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"not found"}}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"## **ការចម្អិនបាយឆា**\n\nOverview.\n\nKey points\n- point one"}]}}]}""",
                ),
            )
            val t = Transcript(
                info("s5", topic = "a cooking show"),
                listOf(TranscriptLine(2_000, 3_000, "Fry the rice.", "ឆាបាយ។"), TranscriptLine(64_000, 66_000, "", "តែការបកប្រែ")),
                null,
                null,
            )
            val summary = Summarizer("key", server.url("/").toString().trimEnd('/')).summarize(t, "Khmer", now = 42)

            assertEquals("ការចម្អិនបាយឆា\n\nOverview.\n\nKey points\n• point one", summary.text)
            assertEquals("ការចម្អិនបាយឆា", summary.title)
            assertEquals(42L, summary.createdAt)

            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1beta/models/gemini-flash-latest:generateContent", first.path)
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", second.path)
            val body = JSONObject(second.body.readUtf8())
            val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            assertTrue(system.contains("Write everything in Khmer."))
            val user = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
            assertTrue(user, user.startsWith("What was being watched: a cooking show\n\nTranscript:\n"))
            assertTrue(user, user.contains("[0:02] Fry the rice.\n"))
            assertTrue(user, user.contains("[1:04] តែការបកប្រែ\n")) // No original: the translation is used.
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pipelineReportsMergedCountsAndUntranslatedSentences() = runTest {
        val failing = object : TextTranslator {
            var fail = true
            override val partialIntervalMs: Long? = null
            override suspend fun prepare(onStatus: (String) -> Unit) = Unit
            override suspend fun translate(text: String, context: List<TranslatedLine>): String {
                if (fail) {
                    fail = false
                    throw IllegalStateException("boom")
                }
                return "T($text)"
            }
            override fun close() = Unit
        }
        val untranslated = mutableListOf<String>()
        val finals = mutableListOf<TranslatedLine>()
        val pipeline = TranslationPipeline(
            backgroundScope, failing, { testScheduler.currentTime },
            object : TranslationPipeline.Listener {
                override fun onPartialTranslation(text: String) = Unit
                override fun onFinalTranslation(line: TranslatedLine) {
                    finals += line
                }
                override fun onUntranslated(text: String) {
                    untranslated += text
                }
            },
        )
        // Queued together, the three sentences go out as one request; it fails, so each is reported.
        pipeline.submitFinal("one.")
        pipeline.submitFinal("two.")
        pipeline.submitFinal("three.")
        runCurrent()
        assertEquals(listOf("one.", "two.", "three."), untranslated)
        // The next two are merged into one translation that says it covers two sentences.
        pipeline.submitFinal("a.")
        pipeline.submitFinal("b.")
        runCurrent()
        assertEquals(1, finals.size)
        assertEquals("a. b.", finals[0].source)
        assertEquals(2, finals[0].parts)
    }
}
