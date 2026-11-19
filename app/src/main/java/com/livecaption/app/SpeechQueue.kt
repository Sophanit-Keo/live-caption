package com.livecaption.app

/**
 * Sentences waiting to be spoken. A spoken translation that lags far behind the video is
 * worse than a skipped one, so only the newest [maxPending] are kept, and speech speeds up
 * while sentences are waiting.
 */
class SpeechQueue(private val maxPending: Int = 2) {
    private val pending = ArrayDeque<String>()

    /** Number of sentences skipped because newer ones arrived. */
    var dropped = 0
        private set

    val size: Int get() = pending.size

    fun add(text: String) {
        if (text.isBlank()) return
        pending.addLast(text)
        while (pending.size > maxPending) {
            pending.removeFirst()
            dropped++
        }
    }

    fun next(): String? = pending.removeFirstOrNull()

    fun clear() = pending.clear()

    /**
     * Speech-rate multiplier for the sentence about to be spoken; [alsoQueued] counts
     * sentences already handed to the speech engine but not finished.
     */
    fun rateFactor(alsoQueued: Int = 0): Float = 1f + RATE_STEP * minOf(pending.size + alsoQueued, maxPending)

    private companion object {
        const val RATE_STEP = 0.15f
    }
}

/**
 * How fast to play Gemini's translated voice. Translated speech arrives in bursts and can
 * pile up behind the video; playing a little faster catches up without dropping words. Each
 * step needs a clear margin before stepping back down, so the speed doesn't flutter.
 */
object PlaybackPacing {
    val SPEEDS = floatArrayOf(1.0f, 1.1f, 1.25f, 1.4f)

    /** Backlog (ms of unheard speech) above which each speed step starts. */
    private val STEP_UP_MS = longArrayOf(0, 1_500, 3_500, 6_000)
    private const val STEP_DOWN_MARGIN_MS = 700L

    /** Above this, old speech is skipped down to [CATCH_UP_TO_MS]. */
    const val SKIP_ABOVE_MS = 12_000L
    const val CATCH_UP_TO_MS = 3_000L

    fun step(backlogMs: Long, currentStep: Int): Int {
        var s = currentStep.coerceIn(0, SPEEDS.size - 1)
        while (s + 1 < SPEEDS.size && backlogMs > STEP_UP_MS[s + 1]) s++
        while (s > 0 && backlogMs < STEP_UP_MS[s] - STEP_DOWN_MARGIN_MS) s--
        return s
    }
}
