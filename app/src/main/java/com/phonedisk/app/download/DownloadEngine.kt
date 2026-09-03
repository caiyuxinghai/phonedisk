package com.phonedisk.app.download

import com.phonedisk.app.util.AppLog
import com.phonedisk.app.util.FileNames
import com.phonedisk.app.util.HtmlPageException
import com.phonedisk.app.util.HttpClients
import com.phonedisk.app.util.LinkResolver
import com.phonedisk.app.util.Storage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
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
    class NoSpace : Exception("空间写满")

    fun probeSize(url: String, referer: String?): Long {
        fun request(builder: Request.Builder): Request {
            builder.header("User-Agent", LinkResolver.UA).header("Accept", "*/*")
            if (!referer.isNullOrBlank()) builder.header("Referer", referer)
            return builder.build()
        }
        val headClient = client.newBuilder().readTimeout(8, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
        try {
            headClient.newCall(request(Request.Builder().url(url).head())).execute().use { resp ->
                plausibleSize(resp.header("Content-Length")?.toLongOrNull() ?: -1L, resp.header("Content-Type"))?.let { return it }
                plausibleSize(parseContentRange(resp.header("Content-Range")) ?: -1L, resp.header("Content-Type"))?.let { return it }
            }
        } catch (_: Exception) {
        }
        try {
            headClient.newCall(request(Request.Builder().url(url).header("Range", "bytes=0-0"))).execute().use { resp ->
                plausibleSize(parseContentRange(resp.header("Content-Range")) ?: -1L, resp.header("Content-Type"))?.let { return it }
                plausibleSize(resp.header("Content-Length")?.toLongOrNull() ?: -1L, resp.header("Content-Type"))?.let { return it }
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    private fun plausibleSize(len: Long, contentType: String?): Long? {
        if (len <= 1L) return null
        if (len > 4L * 1024 * 1024 * 1024 * 1024) return null
        val ct = contentType.orEmpty().lowercase()
        if (ct.contains("text/html") || ct.contains("application/xhtml")) return null
        return len
    }

    private fun parseContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val match = Regex("/(\\d+)\\s*$").find(header) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
    }

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
                AppLog.i("GET ${response.code} ct=$contentType cl=$contentLength ${url.take(96)}")
                if ((contentType.contains("text/html") || contentType.contains("application/xhtml")) && existing == 0L) {
                    val html = body.string().take(400_000)
                    throw HtmlPageException(html, response.request.url.toString())
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

                FileOutputStream(part, append).use { fos ->
                    val channel = fos.channel
                    val input = body.byteStream()
                    val buf = ByteArray(512 * 1024)
                    val wrap = ByteBuffer.wrap(buf)
                    var downloaded = startAt
                    var windowBytes = 0L
                    var windowStart = System.nanoTime()
                    var lastEmit = 0L
                    var lastSpaceCheck = downloaded
                    var ema = 0.0
                    val spaceDir = part.parentFile ?: finalFile.parentFile
                    while (true) {
                        if (cancelFlag.get()) {
                            call.cancel()
                            throw Canceled()
                        }
                        if (pauseFlag.get()) {
                            call.cancel()
                            throw Paused()
                        }
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            if (Storage.isNoSpace(e)) throw NoSpace()
                            throw e
                        }
                        if (n < 0) break
                        try {
                            wrap.clear()
                            wrap.limit(n)
                            while (wrap.hasRemaining()) {
                                channel.write(wrap)
                            }
                        } catch (e: IOException) {
                            if (Storage.isNoSpace(e)) throw NoSpace()
                            throw e
                        }
                        downloaded += n
                        windowBytes += n
                        if (spaceDir != null && downloaded - lastSpaceCheck >= 8L * 1024 * 1024) {
                            val left = Storage.availableBytes(spaceDir)
                            if (left in 0 until 32L * 1024 * 1024) throw NoSpace()
                            lastSpaceCheck = downloaded
                        }
                        val now = System.nanoTime()
                        if (now - lastEmit >= 500_000_000L) {
                            val elapsed = (now - windowStart) / 1_000_000_000.0
                            val instant = if (elapsed > 0) windowBytes / elapsed else 0.0
                            ema = if (ema == 0.0) instant else ema * 0.7 + instant * 0.3
                            onProgress(Progress(downloaded, total, ema.toLong(), nameFromHeader))
                            lastEmit = now
                            windowBytes = 0
                            windowStart = now
                        }
                    }
                    channel.force(false)
                    onProgress(Progress(downloaded, if (total > 0) total else downloaded, 0, nameFromHeader))
                }

                if (finalFile.exists()) finalFile.delete()
                if (!part.renameTo(finalFile)) {
                    if (part.length() > 100L * 1024 * 1024) {
                        throw IOException("文件已下完，但无法改到最终文件名。请检查存储权限，不要把大文件再复制一份。")
                    }
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                }
                return
            }
        }
        throw IllegalStateException("下载失败")
    }
}
