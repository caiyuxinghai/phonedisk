package com.phonedisk.app.download

import com.phonedisk.app.util.FileNames
import com.phonedisk.app.util.HtmlPageException
import com.phonedisk.app.util.HttpClients
import com.phonedisk.app.util.LinkResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class DownloadEngine {
    val client: OkHttpClient = HttpClients.client

    data class Progress(
        val downloaded: Long,
        val total: Long,
        val speedBps: Long,
        val fileName: String?,
    )

    class Canceled : Exception("canceled")
    class Paused : Exception("paused")

    fun download(
        url: String,
        finalFile: File,
        pauseFlag: AtomicBoolean,
        cancelFlag: AtomicBoolean,
        referer: String? = null,
        onProgress: (Progress) -> Unit,
    ) {
        finalFile.parentFile?.mkdirs()
        val part = FileNames.partFile(finalFile)
        if (finalFile.exists() && finalFile.length() > 0 && !part.exists()) {
            onProgress(Progress(finalFile.length(), finalFile.length(), 0, finalFile.name))
            return
        }

        var existing = if (part.exists()) part.length() else 0L
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", LinkResolver.UA)
                .header("Accept", "*/*")
            if (!referer.isNullOrBlank()) {
                builder.header("Referer", referer)
            }
            if (existing > 0) {
                builder.header("Range", "bytes=$existing-")
            }
            val call = client.newCall(builder.build())
            call.execute().use { response ->
                if (cancelFlag.get()) throw Canceled()
                if (pauseFlag.get()) throw Paused()
                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("服务器返回 HTTP ${response.code}")
                }

                val body = response.body ?: throw IllegalStateException("空响应")
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val contentLength = body.contentLength()
                if (contentType.contains("text/html") && contentLength in 0..2_000_000 && existing == 0L) {
                    val html = body.string()
                    throw HtmlPageException(html.take(400_000), response.request.url.toString())
                }

                val rangeOk = response.code == 206
                if (existing > 0 && !rangeOk) {
                    part.delete()
                    existing = 0L
                    if (attempt == 1) return@use
                }

                val append = existing > 0 && rangeOk
                val startAt = if (append) existing else 0L
                val total = when {
                    contentLength < 0 -> -1L
                    append -> startAt + contentLength
                    else -> contentLength
                }
                val nameFromHeader = FileNames.fromDisposition(response.header("Content-Disposition"))

                RandomAccessFile(part, "rw").use { raf ->
                    if (!append) {
                        raf.setLength(0)
                    }
                    raf.seek(startAt)
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var downloaded = startAt
                    var windowBytes = 0L
                    var windowStart = System.nanoTime()
                    var lastEmit = 0L
                    while (true) {
                        if (cancelFlag.get()) {
                            call.cancel()
                            throw Canceled()
                        }
                        if (pauseFlag.get()) {
                            call.cancel()
                            throw Paused()
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        downloaded += n
                        windowBytes += n
                        val now = System.nanoTime()
                        if (now - lastEmit >= 300_000_000L) {
                            val elapsed = (now - windowStart) / 1_000_000_000.0
                            val speed = if (elapsed > 0) (windowBytes / elapsed).toLong() else 0L
                            onProgress(Progress(downloaded, total, speed, nameFromHeader))
                            lastEmit = now
                            windowBytes = 0
                            windowStart = now
                        }
                    }
                    raf.fd.sync()
                    onProgress(Progress(downloaded, if (total > 0) total else downloaded, 0, nameFromHeader))
                }

                if (finalFile.exists()) finalFile.delete()
                if (!part.renameTo(finalFile)) {
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
                return
            }
        }
        throw IllegalStateException("下载失败")
    }
}
