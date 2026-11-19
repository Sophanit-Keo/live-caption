package com.livecaption.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LiveTranslateTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    /** Server side of one WebSocket connection. */
    private class FakeGemini : WebSocketListener() {
        val received = LinkedBlockingQueue<JSONObject>()
        @Volatile var socket: WebSocket? = null
        val opened = LinkedBlockingQueue<Unit>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.add(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(JSONObject(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun next(): JSONObject = received.poll(5, TimeUnit.SECONDS) ?: error("No message from the app")

        /** Gemini sends JSON in binary frames. */
        fun send(json: String) {
            socket!!.send(json.encodeUtf8())
        }
    }

    private fun connection(): FakeGemini = FakeGemini().also {
        server.enqueue(MockResponse().withWebSocketUpgrade(it))
    }

    private val url get() = server.url("/ws").toString().replaceFirst("http", "ws")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdown()
        server.shutdown()
    }

    /** Records session callbacks as strings for easy assertions. */
    private class Events : LiveTranslateSession.Listener {
        val log = LinkedBlockingQueue<String>()
        val audio = LinkedBlockingQueue<ByteArray>()
        override fun onReady(session: LiveTranslateSession) {
            log.add("ready")
        }

        override fun onInputTranscript(session: LiveTranslateSession, text: String, interim: Boolean) {
            log.add(if (interim) "interim:$text" else "input:$text")
        }

        override fun onOutputTranscript(session: LiveTranslateSession, text: String) {
            log.add("output:$text")
        }

        override fun onAudio(session: LiveTranslateSession, pcm: ByteArray, sampleRate: Int) {
            audio.add(pcm)
            log.add("audio@$sampleRate")
        }

        override fun onTurnComplete(session: LiveTranslateSession) {
            log.add("turn")
        }

        override fun onInterrupted(session: LiveTranslateSession) {
            log.add("interrupted")
        }

        override fun onGoAway(session: LiveTranslateSession, timeLeftMs: Long) {
            log.add("goaway:$timeLeftMs")
        }

        override fun onClosed(session: LiveTranslateSession, error: String?, wasReady: Boolean) {
            log.add("closed:$wasReady:$error")
        }

        fun next(): String = log.poll(5, TimeUnit.SECONDS) ?: error("No event")
    }

    @Test
    fun speaksTheDocumentedProtocol() {
        val gemini = connection()
        val events = Events()
        val session = LiveTranslateSession(client, url, "km", events)
        session.start()

        val setup = gemini.next().getJSONObject("setup")
        assertEquals("models/gemini-3.5-live-translate-preview", setup.getString("model"))
        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))
        val config = setup.getJSONObject("generationConfig")
        assertEquals("AUDIO", config.getJSONArray("responseModalities").getString(0))
        val translation = config.getJSONObject("translationConfig")
        assertEquals("km", translation.getString("targetLanguageCode"))
        assertFalse(translation.getBoolean("echoTargetLanguage"))

        // Audio sent before setup completes is held back, then delivered in order.
        val pcm = ByteArray(3200) { it.toByte() }
        session.sendAudio(pcm)
        assertEquals(null, gemini.received.poll(300, TimeUnit.MILLISECONDS))
        gemini.send("""{"setupComplete":{}}""")
        assertEquals("ready", events.next())
        val audio = gemini.next().getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(audio.getString("data")))

        val voice = ByteArray(480) { (it * 7).toByte() }
        gemini.send(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000",
            "data":"${Base64.getEncoder().encodeToString(voice)}"}}]}}}""",
        )
        assertEquals("audio@24000", events.next())
        assertArrayEquals(voice, events.audio.poll(5, TimeUnit.SECONDS))

        gemini.send("""{"serverContent":{"interimInputTranscription":{"text":"Hello wor"}}}""")
        assertEquals("interim:Hello wor", events.next())
        gemini.send("""{"serverContent":{"inputTranscription":{"text":"Hello world","languageCode":"en"}}}""")
        assertEquals("input:Hello world", events.next())
        gemini.send("""{"serverContent":{"outputTranscription":{"text":"សួស្តីពិភពលោក"}}}""")
        assertEquals("output:សួស្តីពិភពលោក", events.next())
        gemini.send("""{"serverContent":{"interrupted":true}}""")
        assertEquals("interrupted", events.next())
        gemini.send("""{"serverContent":{"turnComplete":true}}""")
        assertEquals("turn", events.next())
        gemini.send("""{"goAway":{"timeLeft":"12.5s"}}""")
        assertEquals("goaway:12500", events.next())

        // Pausing tells Gemini the audio stopped, so it translates what it still holds.
        session.endAudioStream()
        assertTrue(gemini.next().getJSONObject("realtimeInput").getBoolean("audioStreamEnd"))

        session.close()
        assertEquals("closed:true:null", events.next())
    }

    @Test
    fun refusedSetupReportsGooglesReason() {
        val gemini = connection()
        val events = Events()
        LiveTranslateSession(client, url, "km", events).start()
        gemini.next() // setup
        gemini.socket!!.close(1008, "API key not valid. Please pass a valid API key.")
        assertEquals("closed:false:API key not valid. Please pass a valid API key.", events.next())
    }

    /** Collects manager events. */
    private class ManagerEvents : LiveTranslator.Events {
        val log = LinkedBlockingQueue<String>()
        override fun onConnected() {
            log.add("connected")
        }

        override fun onReconnecting() {
            log.add("reconnecting")
        }

        override fun onInputTranscript(text: String, interim: Boolean) {
            log.add("input:$text")
        }

        override fun onOutputTranscript(text: String) {
            log.add("output:$text")
        }

        override fun onAudio(pcm: ByteArray, sampleRate: Int) {
            log.add("audio")
        }

        override fun onTurnComplete() = Unit
        override fun onInterrupted() = Unit
        override fun onFatal(message: String) {
            log.add("fatal:$message")
        }

        fun next(): String = log.poll(5, TimeUnit.SECONDS) ?: error("No event")
    }

    private fun manager(events: ManagerEvents) =
        LiveTranslator(scope, { listener -> LiveTranslateSession(client, url, "km", listener) }, events)

    @Test
    fun firstConnectionRefusedIsFatal() {
        val gemini = connection()
        val events = ManagerEvents()
        manager(events).start()
        gemini.next()
        gemini.socket!!.close(1008, "Quota exceeded")
        assertEquals("fatal:Quota exceeded", events.next())
    }

    @Test
    fun goAwayHandsOverToANewSessionWithoutStopping() {
        val first = connection()
        val second = connection()
        val events = ManagerEvents()
        val live = manager(events)
        live.start()
        first.next()
        first.send("""{"setupComplete":{}}""")
        assertEquals("connected", events.next())

        first.send("""{"goAway":{"timeLeft":"10s"}}""")
        assertNotNull(second.opened.poll(5, TimeUnit.SECONDS))
        second.next() // setup of the new session
        // The old session still translates while the new one gets ready.
        first.send("""{"serverContent":{"outputTranscription":{"text":"មុន"}}}""")
        assertEquals("output:មុន", events.next())
        second.send("""{"setupComplete":{}}""")
        assertEquals("connected", events.next())

        live.sendAudio(ByteArray(320))
        assertTrue(second.next().has("realtimeInput"))
        live.stop()
    }

    @Test
    fun oldConnectionEndingBeforeTheNewOneIsReadyIsNotAnError() {
        val first = connection()
        val second = connection()
        val events = ManagerEvents()
        val live = manager(events)
        live.start()
        first.next()
        first.send("""{"setupComplete":{}}""")
        assertEquals("connected", events.next())
        first.send("""{"goAway":{"timeLeft":"1s"}}""")
        second.next() // the replacement is connecting
        first.socket!!.close(1001, "Session time limit reached")
        Thread.sleep(500) // let the app notice the old connection is gone
        live.sendAudio(ByteArray(320)) // goes to the replacement, held until it is ready
        second.send("""{"setupComplete":{}}""")
        assertEquals("connected", events.next()) // no "reconnecting" in between
        assertTrue(second.next().has("realtimeInput"))
        live.stop()
    }

    @Test
    fun droppedConnectionIsRetried() {
        val first = connection()
        val second = connection()
        val events = ManagerEvents()
        val live = manager(events)
        live.start()
        first.next()
        first.send("""{"setupComplete":{}}""")
        assertEquals("connected", events.next())
        first.socket!!.close(1011, "Internal error")
        assertEquals("reconnecting", events.next())
        second.next() // reconnected after the first back-off second
        second.send("""{"setupComplete":{}}""")
        assertEquals("connected", events.next())
        live.stop()
    }

    @Test
    fun parsesDurationsAndSampleRates() {
        assertEquals(1500L, LiveTranslateSession.parseDurationMs("1.5s"))
        assertEquals(2250L, LiveTranslateSession.parseDurationMs(JSONObject("""{"seconds":2,"nanos":250000000}""")))
        assertEquals(16000, LiveTranslateSession.parseSampleRate("audio/pcm;rate=16000"))
        assertEquals(24000, LiveTranslateSession.parseSampleRate("audio/pcm"))
    }
}
