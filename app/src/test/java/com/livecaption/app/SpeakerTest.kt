package com.livecaption.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTest {

    @Test
    fun playsOneSentenceAtATimeAndDropsTheOldestWhenBehind() {
        val queue = SpeechQueue(maxWaiting = 2)
        assertEquals("one", queue.add("one"))
        // "one" keeps playing; the rest wait.
        assertNull(queue.add("two"))
        assertNull(queue.add("three"))
        assertNull(queue.add("four"))
        assertEquals(2, queue.backlog)
        assertEquals("three", queue.next())
        assertEquals("four", queue.next())
        assertNull(queue.next())
        assertFalse(queue.speaking)
        assertEquals("five", queue.add("five"))
        assertTrue(queue.speaking)
    }

    @Test
    fun voiceSpeedsUpSlightlyWithBacklog() {
        assertEquals(1f, speechRate(1f, 0), 0.001f)
        assertEquals(1.1f, speechRate(1f, 1), 0.001f)
        assertEquals(1.2f, speechRate(1f, 10), 0.001f)
        assertEquals(0.88f, speechRate(0.8f, 1), 0.001f)
    }

    private fun voice(name: String, country: String = "KH", quality: Int = 300, latency: Int = 300, network: Boolean = false, installed: Boolean = true) =
        VoiceOption(name, "khm", country, quality, latency, network, installed)

    @Test
    fun choosesTheBestInstalledOfflineVoice() {
        val voices = listOf(
            voice("default"),
            voice("better", quality = 400),
            voice("network", quality = 500, network = true),
            voice("not-downloaded", quality = 500, installed = false),
            VoiceOption("english", "eng", "US", 500, 100, false, true),
        )
        assertEquals("better", bestVoice(voices, "khm", "KH", "default")?.name)
        // Ties keep the engine's own pick.
        assertEquals("default", bestVoice(listOf(voice("a"), voice("default")), "khm", "KH", "default")?.name)
        // With only network voices, one of them is still better than none.
        assertEquals("network", bestVoice(listOf(voice("network", network = true)), "khm", "KH", null)?.name)
        assertNull(bestVoice(voices, "tha", "TH", null))
    }

    @Test
    fun onlyPersonalListeningDevicesCountAsHeadphones() {
        assertEquals(true, Headphones.isHeadphones(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(true, Headphones.isHeadphones(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(false, Headphones.isHeadphones(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(false, Headphones.isHeadphones(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }

    @Test
    fun retriesBackOffExponentially() {
        assertEquals(1_000L, CaptionService.retryDelayMs(1))
        assertEquals(2_000L, CaptionService.retryDelayMs(2))
        assertEquals(4_000L, CaptionService.retryDelayMs(3))
    }
}
