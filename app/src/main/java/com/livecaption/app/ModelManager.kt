package com.livecaption.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
            download(language.url, zip, onProgress)
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

    private suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) throw IOException("Model download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
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
