package com.livecaption.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides what to translate and when. Finished sentences always win over the sentence in
 * progress; if sentences pile up (slow service, rate limit) they are merged into one request
 * instead of being dropped; the last few results are passed along as context.
 *
 * Not thread-safe: create it, and call [submitPartial]/[submitFinal], on [scope]'s single thread.
 */
class TranslationPipeline(
    scope: CoroutineScope,
    private val translator: TextTranslator,
    private val clock: () -> Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartialTranslation(text: String)
        fun onFinalTranslation(line: TranslatedLine)
        fun onRateLimited(retryAfterMs: Long) = Unit
        fun onError(e: Exception) = Unit

        /** A finished sentence that will never be translated (failed, or too stale to show). */
        fun onUntranslated(text: String) = Unit
    }

    private val finals = ArrayDeque<String>()
    private var partial: String? = null

    /** Bumped at every finished sentence so late partial translations are dropped. */
    private var utterance = 0
    private var lastPartialAt = Long.MIN_VALUE / 2
    private var blockedUntil = Long.MIN_VALUE / 2
    private val history = ArrayDeque<TranslatedLine>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val job = scope.launch { run() }

    fun submitPartial(text: String) {
        partial = text
        wake.trySend(Unit)
    }

    fun submitFinal(text: String) {
        finals.addLast(text)
        partial = null
        utterance++
        wake.trySend(Unit)
    }

    fun close() = job.cancel()

    private suspend fun run() {
        while (true) {
            val now = clock()
            if (now < blockedUntil) {
                sleep(blockedUntil - now)
                continue
            }
            if (finals.isNotEmpty()) {
                translateFinals()
                continue
            }
            val p = partial
            val interval = translator.partialIntervalMs
            if (p != null && interval != null && wordCount(p) >= translator.minPartialWords) {
                val wait = lastPartialAt + interval - now
                if (wait > 0) sleep(wait) else translatePartial(p)
                continue
            }
            wake.receive()
        }
    }

    private suspend fun translateFinals() {
        // Stale sentences are not worth showing late: keep the merged request short.
        while (finals.size > 1 && finals.sumOf { it.length + 1 } > MAX_MERGED_CHARS) {
            listener.onUntranslated(finals.removeFirst())
        }
        val count = finals.size
        val text = finals.joinToString(" ")
        try {
            val result = translator.translate(text, history.toList())
            val merged = List(count) { finals.removeFirst() }
            if (result.isBlank()) {
                merged.forEach(listener::onUntranslated)
            } else {
                val line = TranslatedLine(text, result, count)
                history.addLast(line)
                while (history.size > CONTEXT_LINES) history.removeFirst()
                listener.onFinalTranslation(line)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            // Keep the sentences: they are merged with newer ones and sent after the pause.
            backOff(e)
        } catch (e: Exception) {
            repeat(count) { listener.onUntranslated(finals.removeFirst()) }
            listener.onError(e)
        }
    }

    private suspend fun translatePartial(text: String) {
        partial = null
        lastPartialAt = clock()
        val id = utterance
        try {
            val result = translator.translate(text, history.toList())
            if (id == utterance && result.isNotBlank()) listener.onPartialTranslation(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            backOff(e)
        } catch (e: Exception) {
            // A missed in-progress update is harmless; the finished sentence follows.
        }
    }

    private fun backOff(e: RateLimitedException) {
        val wait = e.retryAfterMs.coerceIn(MIN_BACKOFF_MS, MAX_BACKOFF_MS)
        blockedUntil = clock() + wait
        listener.onRateLimited(wait)
    }

    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    private fun wordCount(text: String) = text.split(' ').count { it.isNotBlank() }

    companion object {
        const val CONTEXT_LINES = 3
        const val MAX_MERGED_CHARS = 600
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }
}
