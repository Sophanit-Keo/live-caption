package com.livecaption.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SpeakersTest {

    /** A buzzy voice-like tone: a fundamental at [f0] with falling harmonics, plus a little noise. */
    private fun voice(f0: Double, seconds: Double, amplitude: Double = 0.3, seed: Int = 1): ShortArray {
        val rnd = Random(seed)
        return ShortArray((16_000 * seconds).toInt()) { i ->
            val t = i / 16_000.0
            var s = 0.0
            for (h in 1..6) s += sin(2 * PI * f0 * h * t) / h
            ((s * amplitude / 1.5 + rnd.nextDouble(-0.01, 0.01)) * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun pitchTrackerFindsMenWomenAndChildren() {
        for ((f0, expected) in listOf(115.0 to VoiceType.MAN, 215.0 to VoiceType.WOMAN, 320.0 to VoiceType.CHILD)) {
            val tracker = PitchTracker()
            val audio = voice(f0, 1.5)
            // Fed in uneven chunks, as audio arrives from the recorder.
            var i = 0
            while (i < audio.size) {
                val n = minOf(1_237, audio.size - i)
                tracker.feed(audio.copyOfRange(i, i + n), n)
                i += n
            }
            val median = tracker.take()
            assertNotNull(median)
            assertTrue("$f0 Hz measured as $median", abs(median!! - f0) / f0 < 0.02)
            assertEquals(expected, VoiceType.fromPitch(median))
            assertNull(tracker.take()) // take() starts afresh
        }
    }

    @Test
    fun silenceAndNoiseHaveNoPitch() {
        val tracker = PitchTracker()
        val silence = ShortArray(16_000)
        tracker.feed(silence, silence.size)
        val rnd = Random(3)
        val noise = ShortArray(16_000) { (rnd.nextDouble(-0.3, 0.3) * 32767).toInt().toShort() }
        tracker.feed(noise, noise.size)
        assertNull(tracker.take())
    }

    @Test
    fun voiceTypeThresholds() {
        assertEquals(VoiceType.UNKNOWN, VoiceType.fromPitch(null))
        assertEquals(VoiceType.MAN, VoiceType.fromPitch(140f))
        assertEquals(VoiceType.WOMAN, VoiceType.fromPitch(170f))
        assertEquals(VoiceType.WOMAN, VoiceType.fromPitch(263f)) // a high adult voice is not a child
        assertEquals(VoiceType.CHILD, VoiceType.fromPitch(300f))
    }

    private fun unit(vararg v: Float) = SpeakerTracker.normalized(v)

    @Test
    fun trackerGroupsSimilarVoiceprintsAndSeparatesOthers() {
        val t = SpeakerTracker(threshold = 0.5f)
        val a = SpeakerTracker.normalized(floatArrayOf(1f, 0.1f, 0f))
        val b = SpeakerTracker.normalized(floatArrayOf(0f, 1f, 0.1f))
        assertEquals(SpeakerInfo(1, VoiceType.MAN), t.assign(a, 400, 120f))
        assertEquals(SpeakerInfo(2, VoiceType.WOMAN), t.assign(b, 400, 220f))
        assertEquals(1, t.assign(unit(0.95f, 0.15f, 0.05f), 400, 125f).id)
        // A short sentence stays with the last speaker…
        assertEquals(1, t.assign(null, 0, null).id)
        // …unless its pitch clearly belongs to someone else.
        t.assign(a, 400, 118f)
        assertEquals(SpeakerInfo(null, VoiceType.WOMAN), t.assign(b, 50, 230f))
        assertEquals(2, t.speakerCount)
    }

    @Test
    fun aMansVoiceprintIsNotGivenToAWoman() {
        val t = SpeakerTracker(threshold = 0.3f)
        val v = unit(1f, 0f, 0f)
        t.assign(v, 400, 220f)
        t.assign(v, 400, 225f)
        // Same voiceprint direction, but a clearly low voice: a new speaker.
        assertEquals(2, t.assign(unit(0.9f, 0.3f, 0f), 400, 110f).id)
    }

    @Test
    fun anUnfamiliarVoiceBecomesASpeakerWhenItComesBack() {
        val t = SpeakerTracker(threshold = 0.5f, clearlyNewBelow = 0.2f)
        assertEquals(1, t.assign(unit(1f, 0f, 0f), 400, 120f).id)
        // Somewhat like speaker 1, but not enough: shown as speaker 1 for now, not a new person yet.
        assertEquals(1, t.assign(unit(0.3f, 0.954f, 0f), 400, 125f).id)
        assertEquals(1, t.speakerCount)
        // The same unfamiliar voice again: now it is speaker 2.
        assertEquals(2, t.assign(unit(0.28f, 0.96f, 0.02f), 400, 128f).id)
        assertEquals(2, t.speakerCount)
        // A voice clearly unlike everyone is new at once.
        assertEquals(3, t.assign(unit(0f, 0f, 1f), 400, 118f).id)
    }

    @Test
    fun voiceprintsAreCentredBeforeComparing() {
        val mean = VoiceprintStats.MEAN
        assertEquals(mean.size, VoiceprintStats.SPREAD.size)
        assertTrue(VoiceprintStats.SPREAD.all { it > 0f })
        // The average voice itself carries nothing that tells people apart.
        val v = SpeakerTracker.prepare(FloatArray(mean.size) { mean[it] + if (it == 0) VoiceprintStats.SPREAD[0] else 0f })
        assertEquals(1f, v[0], 1e-4f)
        // Voiceprints of another size (not from the shipped speaker model) are only normalised.
        assertEquals(1f, SpeakerTracker.prepare(floatArrayOf(3f, 4f)).let { it[0] * it[0] + it[1] * it[1] }, 1e-5f)
    }

    /**
     * Real Khmer recordings with known speaker gender (Google FLEURS), as raw 16 kHz PCM with a
     * labels file. Runs only when VOICE_DATA points at that folder.
     */
    @Test
    fun realKhmerVoicesGetTheRightVoiceType() {
        val dir = File(System.getProperty("voice.data").orEmpty())
        assumeTrue(File(dir, "labels.txt").isFile)
        var right = 0
        var wrong = 0
        var total = 0
        for (line in File(dir, "labels.txt").readLines().filter { it.isNotBlank() }) {
            val (name, label) = line.split(',')
            val bytes = File(dir, name).readBytes()
            val samples = ShortArray(bytes.size / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xFF)).toShort() }
            val tracker = PitchTracker()
            samples.toList().chunked(1_600).forEach { chunk -> tracker.feed(chunk.toShortArray(), chunk.size) }
            val frames = tracker.voicedFrames
            val median = tracker.take()
            val voice = VoiceType.fromPitch(median)
            when {
                voice.name == label -> right++
                voice != VoiceType.UNKNOWN -> {
                    wrong++
                    println("VOICES wrong: $name is $label, measured $median Hz ($frames voiced frames)")
                }
            }
            total++
        }
        println("VOICES $right of $total Khmer recordings right, $wrong wrong, ${total - right - wrong} without a verdict")
        // Saying nothing is acceptable for very quiet recordings; a wrong voice type is not.
        assertEquals(0, wrong)
        assertTrue("$right of $total", right >= total * 0.9)
    }

    /**
     * Real voiceprints from 40 LibriSpeech readers, up to 50 recordings each (exported by the
     * calibration script). Runs only when SPEAKERS_DATA points at that file: simulates
     * conversations of 2–4 people taking turns and reports how often each sentence is given to
     * the right person, and how often the right number of people is found.
     */
    @Test
    fun realVoiceprintsInSimulatedConversations() {
        val path = System.getProperty("speakers.data").orEmpty()
        assumeTrue(path.isNotEmpty() && File(path).isFile)
        val segs = File(path).readLines().filter { it.isNotBlank() }.map { line ->
            val (spk, pitch, frames, vec) = line.split(',')
            Seg(spk.toInt(), pitch.toFloat(), frames.toInt(), vec.trim().split(' ').map { it.toFloat() }.toFloatArray())
        }
        val settings = if (System.getProperty("speakers.sweep") == "1") {
            listOf(0.25f, 0.3f, 0.35f, 0.4f).flatMap { t ->
                listOf(100, 200).flatMap { m -> listOf(0f, 0.1f, 1f).map { n -> Triple(t, m, n) } }
            }
        } else {
            listOf(Triple(SpeakerTracker.DEFAULT_THRESHOLD, SpeakerTracker.MIN_FRAMES, SpeakerTracker.CLEARLY_NEW_BELOW))
        }
        for ((threshold, minFrames, clearlyNew) in settings) {
            for (people in 2..4) {
                val r = simulate(segs, people) { SpeakerTracker(threshold = threshold, minFrames = minFrames, clearlyNewBelow = clearlyNew) }
                println(
                    "SPEAKERS t=$threshold min=$minFrames new<$clearlyNew $people people: " +
                        "${pct(r.right, r.total)} of sentences to the right person; right number of people " +
                        "${pct(r.exact, r.trials)}, too many ${pct(r.tooMany, r.trials)}, too few ${pct(r.tooFew, r.trials)} of conversations",
                )
                if (settings.size == 1 && people == 2) {
                    assertTrue("2 people: ${pct(r.right, r.total)}", r.right >= r.total * 0.9)
                }
            }
        }
    }

    private class Seg(val speaker: Int, val pitch: Float, val frames: Int, val vector: FloatArray)

    private class Result(val right: Int, val total: Int, val exact: Int, val tooMany: Int, val tooFew: Int, val trials: Int)

    private fun pct(n: Int, of: Int) = "%.1f%%".format(100.0 * n / of)

    private fun simulate(segs: List<Seg>, people: Int, trials: Int = 200, tracker: () -> SpeakerTracker): Result {
        val bySpeaker = segs.groupBy { it.speaker }
        val rnd = Random(42)
        var right = 0
        var total = 0
        var exact = 0
        var tooMany = 0
        var tooFew = 0
        repeat(trials) {
            val chosen = bySpeaker.keys.shuffled(rnd).take(people)
            val pools = chosen.associateWith { bySpeaker.getValue(it).shuffled(rnd).toMutableList() }
            val t = tracker()
            val labels = ArrayList<Pair<Int, Int?>>()
            // Turns of 1–3 sentences, like a conversation or an interview.
            while (pools.values.any { it.isNotEmpty() }) {
                val who = pools.filterValues { it.isNotEmpty() }.keys.random(rnd)
                repeat(rnd.nextInt(1, 4)) {
                    val seg = pools.getValue(who).removeFirstOrNull() ?: return@repeat
                    labels += seg.speaker to t.assign(seg.vector, seg.frames, seg.pitch.takeIf { p -> p > 0 }).id
                }
            }
            // Each real person is matched to one shown speaker (one-to-one, biggest overlaps
            // first). A person split into two colours, or two people merged into one, loses
            // the sentences that don't fit the match.
            val usedPeople = HashSet<Int>()
            val usedIds = HashSet<Int?>()
            for ((pair, count) in labels.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }) {
                val (person, id) = pair
                if (id == null || person in usedPeople || id in usedIds) continue
                usedPeople += person
                usedIds += id
                right += count
            }
            total += labels.size
            when {
                t.speakerCount == people -> exact++
                t.speakerCount > people -> tooMany++
                else -> tooFew++
            }
        }
        return Result(right, total, exact, tooMany, tooFew, trials)
    }
}
