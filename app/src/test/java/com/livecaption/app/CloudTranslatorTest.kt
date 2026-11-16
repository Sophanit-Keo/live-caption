package com.livecaption.app

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class CloudTranslatorTest {

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
        respond(200, """{"data":{"translations":[{"translatedText":"សួស្តី​ពិភពលោក"}]}}""")
        val result = runBlocking { CloudTranslator("my-key", "en", "km", endpoint).translate("Hello world") }
        assertEquals("សួស្តី​ពិភពលោក", result)
        assertEquals("my-key", requestKey)
        val request = requestBody!!
        assertEquals("Hello world", request.getJSONArray("q").getString(0))
        assertEquals("en", request.getString("source"))
        assertEquals("km", request.getString("target"))
        assertEquals("text", request.getString("format"))
    }

    @Test
    fun reportsTheServiceErrorMessage() {
        respond(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key."}}""")
        val error = assertThrows(IOException::class.java) {
            runBlocking { CloudTranslator("bad", "en", "km", endpoint).prepare() }
        }
        assertEquals("API key not valid. Please pass a valid API key.", error.message)
    }
}
