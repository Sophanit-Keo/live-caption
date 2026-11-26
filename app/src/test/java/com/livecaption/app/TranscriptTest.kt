package com.livecaption.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {

    @Test
    fun capitalizesEnglishAndTheWordI() {
        assertEquals("I think I'm right and i9 is a model", Transcript.tidy("i think i'm right and i9 is a model", "en"))
        assertEquals("I'll see what I've got.", Transcript.finish("  i'll see   what i've got ", "en"))
    }

    @Test
    fun endsFinishedSentencesWithTheLanguagesFullStop() {
        assertEquals("Bonjour tout le monde.", Transcript.finish("bonjour tout le monde", "fr"))
        assertEquals("Is it?", Transcript.finish("is it?", "en"))
        assertEquals("", Transcript.finish("   ", "en"))
        assertEquals("नमस्ते दोस्तों।", Transcript.finish("नमस्ते दोस्तों", "hi"))
    }

    @Test
    fun joinsChineseAndJapaneseWords() {
        assertEquals("我用iPhone拍照", Transcript.tidy("我 用 iPhone 拍照", "zh"))
        assertEquals("我们走吧。", Transcript.finish("我们 走 吧", "zh"))
        assertEquals("今日はいい天気ですね", Transcript.tidy("今日 は いい 天気 です ね", "ja"))
        assertEquals("コーヒーを飲む", Transcript.tidy("コーヒー を 飲む", "ja"))
    }

    @Test
    fun leavesSpacesInLanguagesThatUseThem() {
        assertEquals("오늘 날씨 좋다", Transcript.tidy("오늘 날씨 좋다", "ko"))
        assertEquals("Xin chào các bạn", Transcript.tidy("xin chào các bạn", "vi"))
    }
}
