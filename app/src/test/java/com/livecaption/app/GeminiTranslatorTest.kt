package com.livecaption.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class GeminiTranslatorTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val endpoint get() = "http://127.0.0.1:${server.localPort}/v2"

    private val requestBodies: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    private var requestKey: String? = null

    @After
    fun tearDown() = server.close()

    /** Answers the next HTTP requests in order with [replies] (status, body), recording what was sent. */
    private fun respond(vararg replies: Pair<Int, String>) {
        thread(isDaemon = true) {
            for ((status, body) in replies) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().buffered()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = buildString {
                            while (true) {
                                val c = input.read()
                                if (c < 0 || c == '\n'.code) break
                                if (c != '\r'.code) append(c.toChar())
                            }
                        }
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
                    }
                    val content = input.readNBytes(headers.getValue("content-length").toInt())
                    requestBodies.add(JSONObject(content.toString(Charsets.UTF_8)))
                    requestKey = headers["x-goog-api-key"]

                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
    }

    private fun translator() = GeminiTranslator(null, "my-key", "English", "Khmer", endpoint)

    private fun reply(text: String) = 200 to """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text\n"}]}}]}"""

    @Test
    fun sendsTextAndReturnsKhmerTranslation() {
        respond(reply("សួស្តី​ពិភពលោក"))
        val result = runBlocking { translator().translate("Hello world") }
        assertEquals("សួស្តី​ពិភពលោក", result)
        assertEquals("my-key", requestKey)
        val request = requestBodies.single()
        val sent = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("Hello world", sent.getString("text"))
        val instruction = request.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0)
        assertTrue(instruction.getString("text").contains("from English into Khmer"))
        // Deprecated by Google and ignored by current models; thinking is kept short for speed.
        val config = request.getJSONObject("generationConfig")
        assertFalse(config.has("temperature"))
        assertEquals("low", config.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun sendsEarlierSentencesAsConversationContext() {
        respond(reply("គាត់​ទៅ​ផ្សារ"))
        val history = (1..6).map { TranslatedLine("Sentence $it.", "ប្រយោគ $it") }
        runBlocking { translator().translate("He went to the market.", history) }
        val contents = requestBodies.single().getJSONArray("contents")
        // The last four pairs, then the new sentence last.
        assertEquals(GeminiTranslator.MAX_HISTORY * 2 + 1, contents.length())
        val roles = (0 until contents.length()).map { contents.getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "model", "user", "model", "user", "model", "user", "model", "user"), roles)
        val text = { i: Int -> contents.getJSONObject(i).getJSONArray("parts").getJSONObject(0).getString("text") }
        assertEquals("Sentence 3.", text(0))
        assertEquals("ប្រយោគ 3", text(1))
        assertEquals("He went to the market.", text(8))
    }

    @Test
    fun retriesWithoutThinkingSettingWhenTheModelRejectsIt() {
        respond(
            400 to """{"error":{"code":400,"message":"Thinking level is not supported for this model."}}""",
            reply("សួស្តី"),
            reply("លា"),
        )
        val translator = translator()
        assertEquals("សួស្តី", runBlocking { translator.translate("Hello") })
        assertEquals("លា", runBlocking { translator.translate("Goodbye") })
        assertTrue(requestBodies[0].has("generationConfig"))
        assertFalse(requestBodies[1].has("generationConfig"))
        // Remembered: later requests go out without it straight away.
        assertFalse(requestBodies[2].has("generationConfig"))
    }

    @Test
    fun rateLimitIsRetryableWithTheRequestedDelay() {
        respond(
            429 to """{"error":{"code":429,"message":"Quota exceeded.","status":"RESOURCE_EXHAUSTED","details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}""",
        )
        val error = assertThrows(TranslationException::class.java) { runBlocking { translator().translate("Hello") } }
        assertTrue(error.retryable)
        assertEquals(429, error.status)
        assertEquals(37_000L, error.retryAfterMs)
        assertEquals("Quota exceeded.", error.message)
    }

    @Test
    fun reportsTheServiceErrorMessage() {
        respond(400 to """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}""")
        val error = assertThrows(TranslationException::class.java) {
            runBlocking { GeminiTranslator(null, "bad", "English", "Khmer", endpoint).prepare {} }
        }
        assertEquals("API key not valid. Please pass a valid API key.", error.message)
        assertFalse(error.retryable)
    }

    @Test
    fun noConnectionIsRetryable() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val error = assertThrows(TranslationException::class.java) {
            runBlocking { GeminiTranslator(null, "k", "English", "Khmer", "http://127.0.0.1:$closedPort/v2").translate("Hi") }
        }
        assertTrue(error.retryable)
    }

    @Test
    fun parsesRetryDelays() {
        assertEquals(1_500L, GeminiTranslator.parseRetryDelayMs("""{"error":{"details":[{"@type":"x.RetryInfo","retryDelay":"1.5s"}]}}"""))
        assertEquals(null, GeminiTranslator.parseRetryDelayMs("""{"error":{"message":"busy"}}"""))
        assertEquals(null, GeminiTranslator.parseRetryDelayMs("not json"))
    }

    @Test
    fun namesLanguagesForThePrompt() {
        assertEquals("Khmer", languageName("km"))
        assertEquals("English", languageName("en"))
        assertEquals("Chinese (Simplified)", languageName("zh"))
    }
}
