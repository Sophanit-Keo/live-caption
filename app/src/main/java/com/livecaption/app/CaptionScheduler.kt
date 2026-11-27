package com.livecaption.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs caption updates after a delay, strictly in the order they were posted. Used to show
 * Gemini Live's translated text when its voice actually says it, instead of seconds early.
 * Call from [scope]'s single thread.
 */
class CaptionScheduler(private val scope: CoroutineScope, private val clock: () -> Long) {
    private var lastAt = Long.MIN_VALUE

    /** Runs [block] [delayMs] from now, but never before anything posted earlier. */
    fun post(delayMs: Long, block: () -> Unit) {
        val now = clock()
        val at = maxOf(now + delayMs.coerceAtLeast(0), lastAt)
        lastAt = at
        if (at <= now) {
            block()
            return
        }
        scope.launch {
            delay(at - clock())
            block()
        }
    }
}
