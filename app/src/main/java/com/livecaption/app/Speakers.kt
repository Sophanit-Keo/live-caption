package com.livecaption.app

import kotlin.math.log10
import kotlin.math.sqrt

/** What a voice sounds like, guessed from its pitch. */
enum class VoiceType {
    MAN, WOMAN, CHILD, UNKNOWN;

    companion object {
        /**
         * Thresholds measured on real speech: 222 gender-labelled Khmer recordings (FLEURS) had
         * men at 112–143 Hz and women at 211–236 Hz, so any line from 155 to 180 Hz separated
         * them all; across 40 English readers (LibriSpeech) the median pitches split 20/20 at
         * 170 Hz. One adult woman there was at 263 Hz, so "child" starts higher, at 290 Hz.
         */
        const val MAN_BELOW_HZ = 170f
        const val CHILD_FROM_HZ = 290f

        fun fromPitch(medianHz: Float?): VoiceType = when {
            medianHz == null || medianHz <= 0f -> UNKNOWN
            medianHz < MAN_BELOW_HZ -> MAN
            medianHz >= CHILD_FROM_HZ -> CHILD
            else -> WOMAN
        }

        fun fromName(name: String?): VoiceType = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** Who said a sentence: a speaker number (null when speakers are not told apart) and a voice type. */
data class SpeakerInfo(val id: Int?, val voice: VoiceType) {
    companion object {
        val UNKNOWN = SpeakerInfo(null, VoiceType.UNKNOWN)
    }
}

/**
 * Finds the pitch of voiced speech with the YIN method, 40 ms windows every 20 ms, on 16 kHz
 * audio. Collects the pitches since the last [take] so a sentence's typical pitch can be read.
 */
class PitchTracker(private val sampleRate: Int = 16_000) {
    private val window = FloatArray(FRAME)
    private var filled = 0
    private val pitches = ArrayList<Float>()
    private val diff = FloatArray(TAU_MAX + 1)
    private val cmnd = FloatArray(TAU_MAX + 1)

    /** Voiced 20 ms frames collected since the last [take]. */
    val voicedFrames: Int @Synchronized get() = pitches.size

    @Synchronized
    fun feed(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            window[filled++] = samples[i] / 32768f
            if (filled == FRAME) {
                analyze()?.let { pitches += it }
                // Slide by one hop: the next window overlaps this one by half.
                System.arraycopy(window, HOP, window, 0, FRAME - HOP)
                filled = FRAME - HOP
            }
        }
    }

    /** Median pitch (Hz) of what was collected, or null with too little voiced speech. */
    @Synchronized
    fun median(minFrames: Int = MIN_VOICED_FRAMES): Float? =
        if (pitches.size < minFrames) null else pitches.sorted()[pitches.size / 2]

    /** [median] of what was collected, then starts collecting afresh. */
    @Synchronized
    fun take(minFrames: Int = MIN_VOICED_FRAMES): Float? = median(minFrames).also { pitches.clear() }

    @Synchronized
    fun clear() {
        pitches.clear()
    }

    /** One window: its pitch, or null when it is silent or not voiced. */
    private fun analyze(): Float? {
        var mean = 0f
        for (v in window) mean += v
        mean /= FRAME
        var energy = 0f
        for (v in window) energy += (v - mean) * (v - mean)
        val rms = sqrt(energy / FRAME)
        if (rms <= 0f || 20 * log10(rms) < ENERGY_FLOOR_DB) return null

        val tauMin = sampleRate / MAX_HZ
        val w = FRAME - TAU_MAX
        for (tau in 1..TAU_MAX) {
            var sum = 0f
            for (j in 0 until w) {
                val d = window[j] - window[j + tau]
                sum += d * d
            }
            diff[tau] = sum
        }
        var running = 0f
        cmnd[0] = 1f
        for (tau in 1..TAU_MAX) {
            running += diff[tau]
            cmnd[tau] = if (running > 0f) diff[tau] * tau / running else 1f
        }
        var tau = tauMin
        while (tau <= TAU_MAX) {
            if (cmnd[tau] < THRESHOLD) {
                while (tau + 1 <= TAU_MAX && cmnd[tau + 1] < cmnd[tau]) tau++
                break
            }
            tau++
        }
        if (tau > TAU_MAX) return null
        val shift = if (tau in 1 until TAU_MAX) {
            val a = cmnd[tau - 1]
            val b = cmnd[tau]
            val c = cmnd[tau + 1]
            val denom = a - 2 * b + c
            if (denom != 0f) 0.5f * (a - c) / denom else 0f
        } else {
            0f
        }
        return sampleRate / (tau + shift)
    }

    private companion object {
        const val FRAME = 640 // 40 ms
        const val HOP = 320 // 20 ms
        const val MIN_HZ = 70
        const val MAX_HZ = 500
        const val TAU_MAX = 16_000 / MIN_HZ
        const val THRESHOLD = 0.15f
        /**
         * Quieter frames are ignored. -60 dB, not higher: on 60 gender-labelled Khmer recordings
         * a -45 dB floor left 14 quiet ones without a verdict; at -60 dB 57 were right and none wrong.
         */
        const val ENERGY_FLOOR_DB = -60f

        /** Half a second of voiced speech before guessing a voice type. */
        const val MIN_VOICED_FRAMES = 25
    }
}

/**
 * Tells speakers apart from Vosk's voiceprints (x-vectors), one per finished sentence: a
 * sentence joins the most similar known speaker, or starts a new one. Similarity thresholds
 * come from 40 LibriSpeech readers, where the same person scored a cosine similarity of 0.69
 * (median) and different people 0.22.
 */
class SpeakerTracker(
    private val threshold: Float = DEFAULT_THRESHOLD,
    private val maxSpeakers: Int = 6,
    private val minFrames: Int = MIN_FRAMES,
    /** Below this similarity to everyone known, a voice is clearly new and needs no confirmation. */
    private val clearlyNewBelow: Float = CLEARLY_NEW_BELOW,
) {
    private class Speaker(val id: Int, var centroid: FloatArray, var sentences: Int, val pitches: ArrayList<Float>) {
        fun medianPitch(): Float? = if (pitches.isEmpty()) null else pitches.sorted()[pitches.size / 2]
    }

    private val speakers = ArrayList<Speaker>()
    private var last: Speaker? = null

    /**
     * An unfamiliar voice, not yet a speaker: one odd sentence (a laugh, a shout, background
     * noise) shouldn't create a new person. A second sentence that matches it confirms it.
     */
    private var candidate: Pair<FloatArray, Float?>? = null

    val speakerCount: Int get() = speakers.size

    /**
     * Who said a sentence with voiceprint [vector] ([frames] of speech) and median pitch
     * [pitchHz]. Short sentences give unreliable voiceprints: they stay with the last speaker
     * unless the pitch clearly says otherwise.
     */
    fun assign(vector: FloatArray?, frames: Int, pitchHz: Float?): SpeakerInfo {
        val sentenceVoice = VoiceType.fromPitch(pitchHz)
        if (vector == null || frames < minFrames) {
            val previous = last
            return if (previous != null && compatible(previous, sentenceVoice)) {
                SpeakerInfo(previous.id, voiceOf(previous))
            } else {
                SpeakerInfo(null, sentenceVoice)
            }
        }
        val v = prepare(vector)
        var best: Speaker? = null
        var bestScore = -1f
        for (s in speakers) {
            if (!compatible(s, sentenceVoice)) continue
            val score = dot(v, s.centroid)
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        val pending = candidate
        val speaker = when {
            best != null && bestScore >= threshold -> best.also { update(it, v) }
            speakers.size >= maxSpeakers -> (best ?: speakers.maxBy { dot(v, it.centroid) }).also { update(it, v) }
            // The second sentence of a new voice: it is a new speaker, starting from both sentences.
            pending != null && dot(v, pending.first) >= threshold && sameKind(pending.second, pitchHz) -> {
                candidate = null
                newSpeaker(normalized(FloatArray(v.size) { v[it] + pending.first[it] }))
                    .also { s -> pending.second?.let { s.pitches += it } }
            }
            // The first voice, or one clearly unlike everyone so far (or of another voice type).
            speakers.isEmpty() || best == null || bestScore < clearlyNewBelow -> newSpeaker(v)
            // Probably someone new, not sure yet: keep it in mind, and show the closest speaker.
            else -> {
                candidate = v to pitchHz
                last = best
                return SpeakerInfo(best.id, voiceOf(best))
            }
        }
        pitchHz?.let { speaker.pitches += it }
        last = speaker
        return SpeakerInfo(speaker.id, voiceOf(speaker))
    }

    fun clear() {
        speakers.clear()
        last = null
        candidate = null
    }

    private fun newSpeaker(centroid: FloatArray) =
        Speaker(speakers.size + 1, centroid, 1, ArrayList()).also { speakers += it }

    /** A speaker's voice type from all their sentences, which is steadier than one sentence. */
    private fun voiceOf(s: Speaker) = VoiceType.fromPitch(s.medianPitch())

    /** Two pitches that could be one person: not one man's voice and one woman's or child's. */
    private fun sameKind(a: Float?, b: Float?): Boolean {
        val x = VoiceType.fromPitch(a)
        val y = VoiceType.fromPitch(b)
        return x == VoiceType.UNKNOWN || y == VoiceType.UNKNOWN || (x == VoiceType.MAN) == (y == VoiceType.MAN)
    }

    /** A clearly low voice can't be a speaker with a clearly high one, and the reverse. */
    private fun compatible(s: Speaker, sentence: VoiceType): Boolean {
        val known = voiceOf(s)
        if (sentence == VoiceType.UNKNOWN || known == VoiceType.UNKNOWN || s.pitches.size < 2) return true
        return (sentence == VoiceType.MAN) == (known == VoiceType.MAN)
    }

    private fun update(s: Speaker, v: FloatArray) {
        // A running average, capped so the voiceprint can still adapt to a changing voice.
        val weight = minOf(s.sentences, MAX_WEIGHT).toFloat()
        val merged = FloatArray(v.size) { s.centroid[it] * weight + v[it] }
        s.centroid = normalized(merged)
        s.sentences++
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.45f
        const val MIN_FRAMES = 100 // about one second of speech
        const val CLEARLY_NEW_BELOW = 0.2f
        private const val MAX_WEIGHT = 20

        /**
         * A voiceprint ready to compare: the average voice ([VoiceprintStats]) taken away, each
         * number evened out by its usual spread, and scaled to length 1.
         */
        fun prepare(vector: FloatArray): FloatArray {
            val mean = VoiceprintStats.MEAN
            val spread = VoiceprintStats.SPREAD
            if (vector.size != mean.size) return normalized(vector)
            return normalized(FloatArray(vector.size) { (vector[it] - mean[it]) / spread[it] })
        }

        fun normalized(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            val n = sqrt(sum)
            return if (n > 0f) FloatArray(v.size) { v[it] / n } else v
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
