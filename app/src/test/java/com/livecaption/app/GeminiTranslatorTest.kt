package com.livecaption.app

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GeminiTranslatorTest {

    private lateinit var server: MockWebServer
    private val khmer = TargetLanguage("km", null, "km")

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun translator(target: TargetLanguage = khmer) =
        GeminiTranslator("my-key", "English (US)", target, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun reply(text: String) = MockResponse().setBody(
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text\n"}]}}]}""",
    )

    private fun error(code: Int, message: String) =
        MockResponse().setResponseCode(code).setBody("""{"error":{"code":$code,"message":"$message"}}""")

    private fun takeJson(): Pair<String, JSONObject> {
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("my-key", request.getHeader("x-goog-api-key"))
        return request.path!! to JSONObject(request.body.readUtf8())
    }

    @Test
    fun sendsContextAndReturnsTheTranslation() = runBlocking {
        server.enqueue(reply("ខ្ញុំសុខសប្បាយ។"))
        val context = listOf(TranslatedLine("Hello there.", "សួស្តី។"))
        val result = translator().translate("I am fine", context)
        assertEquals("ខ្ញុំសុខសប្បាយ។", result)

        val (path, body) = takeJson()
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", path)
        val userText = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(userText, userText.contains("Hello there. => សួស្តី។"))
        assertTrue(userText, userText.endsWith("Translate this caption:\nI am fine"))
        val instruction = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction.contains("from English (US) into Khmer"))
        assertTrue(instruction.contains("Do not put spaces between Khmer words"))
        // Deprecated sampling parameters must not be sent (newer models reject them).
        val config = body.getJSONObject("generationConfig")
        assertFalse(config.has("temperature"))
        assertFalse(config.has("thinkingConfig")) // Flash-Lite already thinks minimally.
    }

    @Test
    fun prepareFallsBackWhenAModelIsUnavailable() = runBlocking {
        server.enqueue(error(404, "models/gemini-3.5-flash-lite is not found"))
        server.enqueue(reply("សួស្តី"))
        val t = translator()
        t.prepare {}
        assertEquals("gemini-flash-latest", t.modelId)
        takeJson()
        val (path, body) = takeJson()
        assertEquals("/v1beta/models/gemini-flash-latest:generateContent", path)
        assertEquals("low", body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun prepareDropsTheThinkingLevelWhenTheModelRejectsIt() = runBlocking {
        server.enqueue(error(404, "not found"))
        server.enqueue(error(400, "Thinking level is not supported for this model."))
        server.enqueue(reply("សួស្តី"))
        translator().prepare {}
        takeJson()
        takeJson()
        val (_, body) = takeJson()
        assertFalse(body.getJSONObject("generationConfig").has("thinkingConfig"))
    }

    @Test
    fun wrongKeyFailsWithGooglesMessage() {
        server.enqueue(error(400, "API key not valid. Please pass a valid API key."))
        val e = assertThrows(GeminiException::class.java) { runBlocking { translator().prepare {} } }
        assertEquals("API key not valid. Please pass a valid API key.", e.message)
    }

    @Test
    fun rateLimitReportsTheRetryDelay() {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure"},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"17.5s"}]}}""",
            ),
        )
        val e = assertThrows(RateLimitedException::class.java) { runBlocking { translator().translate("hi") } }
        assertEquals(17_500L, e.retryAfterMs)
    }

    @Test
    fun rateLimitDuringPrepareStillAcceptsTheKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"Quota"}}"""))
        val t = translator()
        t.prepare {}
        assertEquals("gemini-3.5-flash-lite", t.modelId)
    }

    @Test
    fun parsesRepliesAndErrors() {
        val withThoughts = """{"candidates":[{"content":{"parts":[{"text":"plan","thought":true},{"text":" Bonjour "}]}}]}"""
        assertEquals("Bonjour", GeminiTranslator.parseTranslation(withThoughts))
        val blocked = assertThrows(java.io.IOException::class.java) {
            GeminiTranslator.parseTranslation("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        }
        assertTrue(blocked.message!!.contains("SAFETY"))
        assertNull(GeminiTranslator.parseRetryDelayMs("""{"error":{"code":429}}"""))
        assertNull(GeminiTranslator.parseError("not json"))
    }

    @Test
    fun onlyKhmerGetsTheSpacingRule() {
        val french = TargetLanguage("fr", "fr", "fr")
        val body = JSONObject(translator(french).buildRequest(GeminiTranslator.MODELS[0], "hello", emptyList()))
        val instruction = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction.contains("into French"))
        assertFalse(instruction.contains("Khmer"))
    }
}
