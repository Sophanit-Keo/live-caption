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

    /** Speech-rate multiplier for the sentence about to be spoken. */
    fun rateFactor(): Float = 1f + RATE_STEP * minOf(pending.size, maxPending)

    private companion object {
        const val RATE_STEP = 0.15f
    }
}
