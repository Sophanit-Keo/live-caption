package com.livecaption.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CaptionLineTest {

    @Test
    fun liveTextFollowsLastFinishedSentence() {
        val line = CaptionLine()
        line.setLive("hello")
        assertEquals("hello", line.render())
        line.commit("hello world")
        assertEquals("hello world", line.render())
        line.setLive("how are")
        assertEquals("hello world how are", line.render())
        line.commit("how are you")
        assertEquals("how are you", line.render())
    }

    @Test
    fun longTextKeepsTheTailAtAWordBoundary() {
        val line = CaptionLine(maxChars = 12)
        line.commit("alpha bravo charlie delta")
        assertEquals("…delta", line.render())
    }

    @Test
    fun parsesVoskJson() {
        assertEquals("good morning", parseVoskResult("""{"text" : "good morning"}""", "text"))
        assertEquals("good", parseVoskResult("""{"partial" : "good"}""", "partial"))
        assertEquals("", parseVoskResult("""{"partial" : ""}""", "text"))
        assertEquals("", parseVoskResult("not json", "text"))
    }

    @Test
    fun unzipExtractsFilesAndRejectsPathTraversal() {
        val dir = Files.createTempDirectory("unzip").toFile()
        try {
            ModelManager.unzip(zipOf("model/am/final.mdl" to "x", "model/conf/a.conf" to "y"), File(dir, "ok"))
            assertEquals("x", File(dir, "ok/model/am/final.mdl").readText())
            assertThrows(IOException::class.java) {
                ModelManager.unzip(zipOf("../evil.txt" to "z"), File(dir, "bad"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
