package com.livecaption.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {

    @Test
    fun splitsEnglishButNotNumbersAbbreviationsOrInitials() {
        assertEquals(
            listOf("Mr. Smith paid 3.5 dollars.", "Is that right?", "Yes!", "J. K. Rowling wrote it."),
            Sentences.split("Mr. Smith paid 3.5 dollars. Is that right? Yes! J. K. Rowling wrote it."),
        )
        assertEquals(listOf("Wait...", "what?!"), Sentences.split("Wait... what?!"))
        assertEquals(listOf("He said \"stop.\"", "Then he left."), Sentences.split("He said \"stop.\" Then he left."))
        assertEquals(listOf("no punctuation at all"), Sentences.split("no punctuation at all"))
        assertEquals(emptyList<String>(), Sentences.split("   "))
    }

    @Test
    fun splitsKhmerChineseAndJapaneseWithoutSpaces() {
        assertEquals(listOf("សួស្តី។", "តើអ្នកសុខសប្បាយជាទេ?"), Sentences.split("សួស្តី។ តើអ្នកសុខសប្បាយជាទេ?"))
        assertEquals(listOf("សួស្តី។", "ខ្ញុំសុខសប្បាយ។"), Sentences.split("សួស្តី។ខ្ញុំសុខសប្បាយ។"))
        assertEquals(listOf("你好。", "你今天好吗？"), Sentences.split("你好。你今天好吗？"))
        assertEquals(listOf("こんにちは。", "元気ですか？"), Sentences.split("こんにちは。元気ですか？"))
    }

    @Test
    fun streamingWaitsBeforeTreatingTheLastMarkAsAnEnd() {
        assertEquals(emptyList<Int>(), Sentences.boundaries("It costs 3.", streaming = true))
        assertEquals(listOf(11), Sentences.boundaries("It costs 3.", streaming = false))
        assertEquals(listOf(6), Sentences.boundaries("Hello. Wor", streaming = true))
        assertEquals(emptyList<Int>(), Sentences.boundaries("Really?", streaming = true))
    }

    @Test
    fun streamingFragmentsBecomeSentencesAsTheyFinish() {
        val b = SentenceBuffer { 0L }
        b.append("It costs 3.")
        b.append("5 dollars. And then")
        assertEquals(listOf("It costs 3.5 dollars."), b.snapshot().done)
        assertEquals("And then", b.snapshot().current)
        b.append(" we left.")
        assertEquals("And then we left.", b.snapshot().current) // Not finished until more text or a pause.
        b.endSegment()
        assertEquals(listOf("It costs 3.5 dollars.", "And then we left."), b.snapshot().done)
        assertEquals("", b.snapshot().current)
        // Khmer fragments with no spaces stay joined correctly.
        b.append("សួស្តី។")
        b.append("ខ្ញុំ")
        b.append("ចង់ទៅផ្សារ។")
        assertEquals("សួស្តី។", b.snapshot().done.last())
        assertEquals("ខ្ញុំចង់ទៅផ្សារ។", b.snapshot().current)
    }

    @Test
    fun committedTranslationsAreSplitAndOnlyRecentOnesKept() {
        val b = SentenceBuffer(maxSentences = 3) { 0L }
        b.commit("One. Two.")
        b.commit("Three. Four.")
        assertEquals(listOf("Two.", "Three.", "Four."), b.snapshot().done)
        assertEquals("Four.", b.snapshot().newest().text)
    }

    @Test
    fun fitsAsManyWholeRecentSentencesAsTheLinesAllow() {
        // Lines each sentence takes when wrapped: oldest 2, then 1, then 1 (newest).
        val lines = listOf(2, 1, 1)
        fun linesFrom(start: Int) = lines.subList(start, lines.size).sum()
        assertEquals(2, Sentences.fitNewest(3, maxLines = 1, ::linesFrom)) // newest only
        assertEquals(1, Sentences.fitNewest(3, maxLines = 2, ::linesFrom)) // last two
        assertEquals(1, Sentences.fitNewest(3, maxLines = 3, ::linesFrom)) // the oldest needs 2 more
        assertEquals(0, Sentences.fitNewest(3, maxLines = 4, ::linesFrom)) // all
        assertEquals(0, Sentences.fitNewest(1, maxLines = 1) { 5 }) // the newest is kept even when too long
    }
}
