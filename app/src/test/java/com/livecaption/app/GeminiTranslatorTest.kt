package com.livecaption.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class GeminiTranslatorTest {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val endpoint get() = "http://127.0.0.1:${server.localPort}/v2"

    private var requestBody: JSONObject? = null
    private var requestKey: String? = null

    @After
    fun tearDown() = server.close()

    /** Answers one HTTP request with [status] and [body], recording what was sent. */
    private fun respond(status: Int, body: String) {
        thread(isDaemon = true) {
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
                requestBody = JSONObject(content.toString(Charsets.UTF_8))
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

    @Test
    fun sendsTextAndReturnsKhmerTranslation() {
        respond(200, """{"candidates":[{"content":{"role":"model","parts":[{"text":"សួស្តី​ពិភពលោក\n"}]}}]}""")
        val result = runBlocking { GeminiTranslator(null, "my-key", "English", "Khmer", endpoint).translate("Hello world") }
        assertEquals("សួស្តី​ពិភពលោក", result)
        assertEquals("my-key", requestKey)
        val request = requestBody!!
        val sent = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("Hello world", sent.getString("text"))
        val instruction = request.getJSONObject("system_instruction").getJSONArray("parts").getJSONObject(0)
        assertTrue(instruction.getString("text").contains("from English into Khmer"))
    }

    @Test
    fun reportsTheServiceErrorMessage() {
        respond(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}""")
        val error = assertThrows(IOException::class.java) {
            runBlocking { GeminiTranslator(null, "bad", "English", "Khmer", endpoint).prepare {} }
        }
        assertEquals("API key not valid. Please pass a valid API key.", error.message)
    }
}
