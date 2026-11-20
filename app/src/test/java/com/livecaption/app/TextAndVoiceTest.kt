package com.livecaption.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndVoiceTest {

    private val zwsp = "​"

    @Test
    fun chineseAndJapaneseTranscriptsLoseWordSpaces() {
        assertEquals("今天天气很好", TextTidy.transcript("今天 天气 很 好", "zh"))
        assertEquals("これはテストです", TextTidy.transcript("これ は テスト です", "ja"))
        assertEquals("hello world", TextTidy.transcript(" hello world ", "en"))
        // Latin words inside Chinese keep their spaces.
        assertEquals("我用 iPhone 手机", TextTidy.transcript("我 用 iPhone 手机", "zh"))
    }

    @Test
    fun finishedSpeechBecomesASentence() {
        assertEquals("Hello my friend.", TextTidy.sentence("hello my friend", "en"))
        assertEquals("How are you today?", TextTidy.sentence("how are you today", "en"))
        assertEquals("Already done!", TextTidy.sentence("already done!", "en"))
        assertEquals("今天天气很好。", TextTidy.sentence("今天 天气 很 好", "zh"))
        assertEquals("Bonjour tout le monde.", TextTidy.sentence("bonjour tout le monde", "fr"))
        assertEquals("", TextTidy.sentence("  ", "en"))
    }

    @Test
    fun wordSplitKhmerIsJoinedAndPhraseSpacingKept() {
        assertEquals(
            listOf("ខ្ញុំ", "គិត", "ថា", "នេះ", "ជា", "ទូរស័ព្ទ", "ល្អ", "បំផុត", "ក្នុង", "ឆ្នាំ។").joinToString(zwsp),
            TextTidy.khmer("ខ្ញុំ គិត ថា នេះ ជា ទូរស័ព្ទ ល្អ បំផុត ក្នុង ឆ្នាំ ។"),
        )
        val phrases = "អរគុណយ៉ាងខ្លាំង ដែលបានមើល ហើយ កុំភ្លេចចុះឈ្មោះ"
        assertEquals(phrases, TextTidy.khmer(phrases))
        // Spaces next to Latin names stay.
        assertEquals(
            "ខ្ញុំ${zwsp}មាន${zwsp}ឈ្មោះ${zwsp}ថា John ហើយ${zwsp}ខ្ញុំ${zwsp}រស់",
            TextTidy.khmer("ខ្ញុំ មាន ឈ្មោះ ថា John ហើយ ខ្ញុំ រស់"),
        )
    }

    @Test
    fun speechQueueKeepsOnlyTheNewestAndSpeedsUp() {
        val q = SpeechQueue<String>(maxPending = 2) { it.isBlank() }
        assertEquals(1f, q.rateFactor())
        q.add("one")
        q.add("two")
        q.add("three")
        q.add(" ")
        assertEquals(1, q.dropped)
        assertEquals(1.3f, q.rateFactor(), 0.001f)
        assertEquals("two", q.next())
        assertEquals(1.15f, q.rateFactor(), 0.001f)
        assertEquals("three", q.next())
        assertNull(q.next())
    }

    @Test
    fun catalogCombinesMlKitKhmerAndLiveLanguages() {
        val catalog = TargetLanguage.catalog(listOf("en", "zh", "tl", "fr", "cy"))
        val byTag = catalog.associateBy { it.tag }

        val km = byTag.getValue("km")
        assertTrue(km.supports(TranslationEngine.OFFLINE)) // via the NLLB model
        assertTrue(km.supports(TranslationEngine.GEMINI))
        assertTrue(km.supports(TranslationEngine.LIVE))
        assertEquals("Khmer", km.englishName)

        assertEquals("zh-Hans", byTag.getValue("zh").live)
        assertEquals("fil", byTag.getValue("tl").live)
        assertNull(byTag.getValue("cy").live) // Welsh: ML Kit only.
        assertFalse(byTag.getValue("cy").supports(TranslationEngine.LIVE))

        val lao = byTag.getValue("lo") // Live-only language.
        assertFalse(lao.supports(TranslationEngine.OFFLINE))
        assertTrue(lao.supports(TranslationEngine.GEMINI))

        // Every Live language appears exactly once, and Chinese is not listed twice.
        val liveCodes = catalog.mapNotNull { it.live }
        assertEquals(liveCodes.size, liveCodes.toSet().size)
        assertEquals(TargetLanguage.LIVE_CODES, liveCodes.toSet())
        assertFalse(byTag.containsKey("zh-Hans"))
    }

    @Test
    fun streamingTextJoinsFragmentsAndStartsFreshAfterSilence() {
        var now = 0L
        val t = SentenceBuffer(resetAfterMs = 8_000) { now }
        t.append("Hello")
        t.append(" world")
        assertEquals("Hello world", t.snapshot().current)
        t.setPending("and mo")
        assertEquals("Hello world and mo", t.snapshot().current)
        t.append(" and more")
        assertEquals("Hello world and more", t.snapshot().current)

        now = 20_000
        t.append("Next")
        assertEquals(SentenceSnapshot.of(emptyList(), "Next"), t.snapshot())

        // A pause closes the sentence; the next words start a new one.
        t.endSegment()
        t.append("សួស្តី")
        assertEquals(listOf("Next"), t.snapshot().done.map { it.text })
        assertEquals("សួស្តី", t.snapshot().current)
    }

    @Test
    fun nllbCutsDecodingLoopsButKeepsRealRepeats() {
        assertEquals(6, NllbTranslator.loopCut(listOf(1, 2, 3, 4, 5, 6, 3, 4, 5, 6))) // "3 4 5 6" twice → keep one
        assertEquals(2, NllbTranslator.loopCut(listOf(1, 9, 9, 9))) // one token three times
        assertNull(NllbTranslator.loopCut(listOf(1, 9, 9))) // a doubled word is fine
        assertNull(NllbTranslator.loopCut(listOf(5, 6, 7, 1, 5, 6, 7))) // a word repeated later is fine
        assertNull(NllbTranslator.loopCut(emptyList()))
    }

    @Test
    fun headphoneTypes() {
        assertTrue(HeadphoneMonitor.isHeadphone(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(HeadphoneMonitor.isHeadphone(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertFalse(HeadphoneMonitor.isHeadphone(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}
