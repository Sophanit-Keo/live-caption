package com.livecaption.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Base64

/**
 * One connection to Gemini Live Translate: streams 16 kHz speech in and receives translated
 * speech (24 kHz PCM) plus transcripts of both sides. Callbacks arrive on OkHttp's thread.
 */
class LiveTranslateSession(
    private val client: OkHttpClient,
    private val url: String,
    private val targetLanguage: String,
    private val listener: Listener,
    /** Optional guidance for the interpreter, e.g. what is being watched. */
    private val systemInstruction: String? = null,
) : WebSocketListener() {

    interface Listener {
        fun onReady(session: LiveTranslateSession)
        fun onInputTranscript(session: LiveTranslateSession, text: String, interim: Boolean)
        fun onOutputTranscript(session: LiveTranslateSession, text: String)
        fun onAudio(session: LiveTranslateSession, pcm: ByteArray, sampleRate: Int)
        fun onTurnComplete(session: LiveTranslateSession)
        fun onInterrupted(session: LiveTranslateSession)
        fun onGoAway(session: LiveTranslateSession, timeLeftMs: Long)

        /** [error] is null when we closed it ourselves. */
        fun onClosed(session: LiveTranslateSession, error: String?, wasReady: Boolean)
    }

    @Volatile var isReady = false
        private set

    @Volatile private var closedByUs = false
    @Volatile private var finished = false
    private var socket: WebSocket? = null
    private val early = ArrayDeque<String>()

    fun start() {
        socket = client.newWebSocket(Request.Builder().url(url).build(), this)
    }

    /** Sends 16-bit little-endian mono PCM at 16 kHz; audio sent before the session is ready is buffered briefly. */
    fun sendAudio(pcm: ByteArray) {
        val message = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(pcm)).put("mimeType", INPUT_MIME)),
        ).toString()
        synchronized(early) {
            if (!isReady) {
                early.addLast(message)
                while (early.size > MAX_EARLY_CHUNKS) early.removeFirst()
                return
            }
        }
        socket?.send(message)
    }

    /**
     * The audio stops for a while (captions paused): lets Gemini translate what it still holds.
     * Sending audio again reopens the stream.
     */
    fun endAudioStream() {
        if (isReady) socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
    }

    fun close() {
        closedByUs = true
        socket?.close(1000, null)
    }

    fun setupMessage(): String {
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "translationConfig",
                JSONObject().put("targetLanguageCode", targetLanguage).put("echoTargetLanguage", false),
            )
        val setup = JSONObject()
            .put("model", "models/$MODEL")
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("generationConfig", generationConfig)
        if (!systemInstruction.isNullOrBlank()) {
            setup.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
        }
        return JSONObject().put("setup", setup).toString()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(setupMessage())
    }

    // The service sends JSON in binary frames.
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

    private fun handle(text: String) {
        val message = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }
        if (message.has("setupComplete")) {
            val pending = synchronized(early) {
                isReady = true
                early.toList().also { early.clear() }
            }
            pending.forEach { socket?.send(it) }
            listener.onReady(this)
        }
        message.optJSONObject("serverContent")?.let(::handleContent)
        message.optJSONObject("goAway")?.let { listener.onGoAway(this, parseDurationMs(it.opt("timeLeft"))) }
    }

    private fun handleContent(content: JSONObject) {
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val data = inline.optString("data")
                if (data.isEmpty()) continue
                listener.onAudio(this, Base64.getMimeDecoder().decode(data), parseSampleRate(inline.optString("mimeType")))
            }
        }
        content.optJSONObject("interimInputTranscription")?.optString("text")?.let {
            listener.onInputTranscript(this, it, interim = true)
        }
        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            listener.onInputTranscript(this, it, interim = false)
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            listener.onOutputTranscript(this, it)
        }
        if (content.optBoolean("interrupted")) listener.onInterrupted(this)
        if (content.optBoolean("turnComplete")) listener.onTurnComplete(this)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        finish(if (closedByUs) null else closeReason(code, reason))
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        finish(if (closedByUs) null else closeReason(code, reason))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        val error = when {
            closedByUs -> null
            response != null -> "Gemini Live refused the connection (HTTP ${response.code})" +
                (runCatching { response.body?.string() }.getOrNull()?.let { GeminiTranslator.parseError(it) }?.let { ": $it" } ?: "")
            else -> t.message ?: "Connection to Gemini Live failed"
        }
        finish(error)
    }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        listener.onClosed(this, error, isReady)
    }

    private fun closeReason(code: Int, reason: String) =
        reason.takeIf { it.isNotBlank() } ?: "Gemini Live closed the connection (code $code)"

    companion object {
        const val MODEL = "gemini-3.5-live-translate-preview"
        const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val INPUT_MIME = "audio/pcm;rate=16000"
        const val OUTPUT_SAMPLE_RATE = 24_000

        /** About two seconds of 100 ms chunks. */
        private const val MAX_EARLY_CHUNKS = 20

        fun parseSampleRate(mimeType: String): Int =
            Regex("rate=(\\d+)").find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: OUTPUT_SAMPLE_RATE

        /** Protobuf JSON durations look like "12.5s"; an object form {seconds, nanos} is accepted too. */
        fun parseDurationMs(value: Any?): Long = when (value) {
            is String -> value.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            is JSONObject -> value.optLong("seconds") * 1000 + value.optLong("nanos") / 1_000_000
            is Number -> value.toLong() * 1000
            else -> 0L
        }
    }
}

/**
 * Keeps a Gemini Live Translate session running for as long as captions are on: Google ends
 * each connection after about ten minutes (announced by a GoAway message), so a new session is
 * opened in advance and audio switches over once it is ready. Dropped connections are retried.
 * Every method and callback runs on [scope]'s thread, except [sendAudio].
 */
class LiveTranslator(
    private val scope: CoroutineScope,
    private val sessionFactory: (LiveTranslateSession.Listener) -> LiveTranslateSession,
    private val events: Events,
) {
    interface Events {
        fun onConnected()
        fun onReconnecting()
        fun onInputTranscript(text: String, interim: Boolean)
        fun onOutputTranscript(text: String)
        fun onAudio(pcm: ByteArray, sampleRate: Int)
        fun onTurnComplete()
        fun onInterrupted()

        /** The session cannot work (for example a wrong key or no quota); captions should stop. */
        fun onFatal(message: String)
    }

    @Volatile private var active: LiveTranslateSession? = null
    private var standby: LiveTranslateSession? = null
    private val retiring = mutableSetOf<LiveTranslateSession>()
    private var everReady = false
    private var failures = 0
    private var stopped = false
    private var retryJob: Job? = null

    fun start() {
        active = open()
    }

    /** Audio thread. */
    fun sendAudio(pcm: ByteArray) {
        active?.sendAudio(pcm)
    }

    /** Audio stops for a while (paused); see [LiveTranslateSession.endAudioStream]. */
    fun endAudioStream() {
        active?.endAudioStream()
    }

    fun stop() {
        stopped = true
        retryJob?.cancel()
        listOfNotNull(active, standby).plus(retiring).forEach { it.close() }
        active = null
        standby = null
        retiring.clear()
    }

    private fun open(): LiveTranslateSession = sessionFactory(listener).also { it.start() }

    private fun handleReady(session: LiveTranslateSession) {
        failures = 0
        if (session === standby) {
            // Switch audio to the new session; let the old one finish speaking, then close it.
            val old = active
            active = session
            standby = null
            if (old != null) {
                retiring += old
                scope.launch {
                    delay(RETIRE_AFTER_MS)
                    old.close()
                }
            }
        }
        if (session === active) {
            everReady = true
            events.onConnected()
        }
    }

    private fun handleClosed(session: LiveTranslateSession, error: String?, wasReady: Boolean) {
        if (retiring.remove(session) || stopped) return
        if (session === standby) {
            standby = null // The current session keeps working until its own end.
            return
        }
        if (session !== active) return
        active = null
        if (error == null) return
        standby?.let {
            // A replacement is already connecting (after a GoAway): it takes over.
            active = it
            standby = null
            return
        }
        if (!everReady && !wasReady) {
            // The very first connection was refused: a wrong key, an unsupported language, no quota…
            events.onFatal(error)
            return
        }
        failures++
        if (failures > MAX_RETRIES) {
            events.onFatal(error)
            return
        }
        events.onReconnecting()
        retryJob = scope.launch {
            delay(minOf(MAX_RETRY_DELAY_MS, 1_000L shl (failures - 1)))
            if (!stopped && active == null) active = open()
        }
    }

    private fun handleGoAway(session: LiveTranslateSession) {
        if (session === active && standby == null && !stopped) standby = open()
    }

    private val listener = object : LiveTranslateSession.Listener {
        override fun onReady(session: LiveTranslateSession) = post { handleReady(session) }
        override fun onInputTranscript(session: LiveTranslateSession, text: String, interim: Boolean) =
            post { if (isCurrent(session)) events.onInputTranscript(text, interim) }
        override fun onOutputTranscript(session: LiveTranslateSession, text: String) =
            post { if (isCurrent(session)) events.onOutputTranscript(text) }
        override fun onAudio(session: LiveTranslateSession, pcm: ByteArray, sampleRate: Int) =
            post { if (isCurrent(session)) events.onAudio(pcm, sampleRate) }
        override fun onTurnComplete(session: LiveTranslateSession) = post { if (isCurrent(session)) events.onTurnComplete() }
        override fun onInterrupted(session: LiveTranslateSession) = post { if (isCurrent(session)) events.onInterrupted() }
        override fun onGoAway(session: LiveTranslateSession, timeLeftMs: Long) = post { handleGoAway(session) }
        override fun onClosed(session: LiveTranslateSession, error: String?, wasReady: Boolean) =
            post { handleClosed(session, error, wasReady) }
    }

    /** The new session and the one being retired may both still deliver translations. */
    private fun isCurrent(session: LiveTranslateSession) = !stopped && (session === active || session in retiring)

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    companion object {
        private const val MAX_RETRIES = 5
        private const val MAX_RETRY_DELAY_MS = 15_000L
        private const val RETIRE_AFTER_MS = 4_000L

        fun url(apiKey: String) = "${LiveTranslateSession.ENDPOINT}?key=$apiKey"

        /**
         * Translation guidance from the user's description of what they are watching. Live
         * Translate only accepts guidance about the translation itself (terms, register).
         */
        fun instructionFor(topic: String, targetName: String): String? =
            topic.trim().takeIf { it.isNotEmpty() }?.let {
                "The audio is from: $it. Translate it into natural spoken $targetName, " +
                    "keeping names and specialist terms accurate."
            }
    }
}
