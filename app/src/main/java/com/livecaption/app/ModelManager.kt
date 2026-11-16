package com.livecaption.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Downloads and unpacks Vosk speech models into app-private storage. */
object ModelManager {

    private fun modelsDir(context: Context) = File(context.filesDir, "models")

    fun isInstalled(context: Context, language: SpeechLanguage): Boolean =
        File(modelsDir(context), language.modelName).isDirectory

    /**
     * Returns the model directory, downloading it first if needed.
     * [onProgress] gets 0..100 while downloading, then -1 while unpacking.
     */
    suspend fun ensure(
        context: Context,
        language: SpeechLanguage,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val root = modelsDir(context).apply { mkdirs() }
        val target = File(root, language.modelName)
        if (target.isDirectory) return@withContext target

        val zip = File(context.cacheDir, "${language.modelName}.zip")
        val staging = File(root, "${language.modelName}.tmp")
        try {
            var lastPercent = -1
            downloadFile(language.url, zip) { done ->
                val percent = (done * 100 / (language.sizeMb * 1024L * 1024L)).toInt().coerceAtMost(99)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            }
            onProgress(-1)
            staging.deleteRecursively()
            zip.inputStream().use { unzip(it, staging) }
            // Archives contain a single top-level folder holding the model.
            val inner = staging.listFiles()?.singleOrNull { it.isDirectory } ?: staging
            if (!inner.renameTo(target)) throw IOException("Could not install speech model")
            target
        } finally {
            zip.delete()
            staging.deleteRecursively()
        }
    }

    /**
     * Downloads [url] to [dest], resuming a previous partial download. Does nothing if
     * [dest] already exists. [onProgress] receives the number of bytes on disk so far.
     */
    suspend fun downloadFile(url: String, dest: File, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        if (dest.isFile) return@withContext
        val part = File(dest.parentFile, dest.name + ".part")
        var done = if (part.isFile) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit
                HttpURLConnection.HTTP_OK -> done = 0 // Server ignored the range: start over.
                416 -> { // Our partial file is not a valid prefix: discard it and fail this attempt.
                    part.delete()
                    throw IOException("Download could not be resumed, please try again")
                }
                else -> throw IOException("Download failed (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                FileOutputStream(part, done > 0).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var reported = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - reported >= 512 * 1024) {
                            reported = done
                            onProgress(done)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(dest)) throw IOException("Could not save ${dest.name}")
    }

    fun unzip(input: InputStream, destDir: File) {
        destDir.mkdirs()
        val base = destDir.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(base)) throw IOException("Unsafe path in archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
            }
        }
    }
}
