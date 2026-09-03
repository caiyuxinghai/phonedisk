package com.phonedisk.app.share

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors

class LanShareServer(
    private val root: File,
    val port: Int = 8765,
    private val token: String? = null,
) {
    @Volatile
    private var server: ServerSocket? = null
    private var pool = Executors.newCachedThreadPool()

    val running: Boolean
        get() = server?.isClosed == false

    fun start() {
        if (running) return
        if (pool.isShutdown || pool.isTerminated) {
            pool = Executors.newCachedThreadPool()
        }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        pool.execute {
            while (true) {
                val current = server ?: break
                if (current.isClosed) break
                try {
                    val socket = current.accept()
                    pool.execute { handle(socket) }
                } catch (_: Exception) {
                    if (server == null || server?.isClosed == true) break
                }
            }
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())
            val headerText = readHeaders(input)
            val requestLine = headerText.lineSequence().firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("GET", true)) {
                writeText(out, 405, "Method Not Allowed")
                return
            }
            val target = parts[1]
            val rawPath = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val path = try {
                URLDecoder.decode(rawPath, "UTF-8")
            } catch (_: Exception) {
                rawPath
            }
            val provided = queryParam(query, "k") ?: cookieValue(headerText, "pd")
            val authed = token.isNullOrBlank() || provided == token
            if (!authed) {
                writeText(out, 401, loginHtml(), "text/html; charset=utf-8")
                return
            }
            val setCookie = if (!token.isNullOrBlank() && provided == token) {
                "Set-Cookie: pd=$token; Path=/; HttpOnly\r\n"
            } else {
                ""
            }
            when {
                path == "/" || path.isEmpty() -> writeText(out, 200, indexHtml(), "text/html; charset=utf-8", setCookie)
                path.startsWith("/f/") -> {
                    val name = path.removePrefix("/f/")
                    val file = File(root, name)
                    val canonical = try {
                        file.canonicalFile
                    } catch (_: Exception) {
                        writeText(out, 400, "Bad path")
                        return
                    }
                    val rootCanon = root.canonicalFile
                    if (!canonical.path.startsWith(rootCanon.path + File.separator) &&
                        canonical.path != rootCanon.path
                    ) {
                        writeText(out, 403, "Forbidden")
                        return
                    }
                    if (!canonical.isFile) {
                        writeText(out, 404, "Not Found")
                        return
                    }
                    writeFile(out, canonical, setCookie)
                }
                else -> writeText(out, 404, "Not Found")
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        var last = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            if (last == '\r'.code && b == '\n'.code && buf.size() >= 4) {
                val bytes = buf.toByteArray()
                val n = bytes.size
                if (bytes[n - 4] == '\r'.code.toByte() &&
                    bytes[n - 3] == '\n'.code.toByte() &&
                    bytes[n - 2] == '\r'.code.toByte() &&
                    bytes[n - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            last = b
            if (buf.size() > 16_384) break
        }
        return buf.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun indexHtml(): String {
        val files = root.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            .orEmpty()
        val rows = if (files.isEmpty()) {
            "<p>还没有下完的文件。</p>"
        } else {
            buildString {
                append("<ul>")
                for (f in files) {
                    val q = if (token.isNullOrBlank()) "" else "?k=" + URLEncoder.encode(token, "UTF-8")
                    val href = "/f/" + URLEncoder.encode(f.name, "UTF-8").replace("+", "%20") + q
                    val size = human(f.length())
                    append("<li><a href=\"$href\">${escape(f.name)}</a> <span>${escape(size)}</span></li>")
                }
                append("</ul>")
            }
        }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>随身下载盘</title>
              <style>
                body { font-family: sans-serif; background:#121418; color:#eee; margin:24px; }
                a { color:#FFB74D; }
                li { margin: 10px 0; }
                span { color:#9aa; margin-left:8px; }
              </style>
            </head>
            <body>
              <h1>随身下载盘</h1>
              <p>点文件名即可拷到这台电脑。</p>
              $rows
            </body>
            </html>
        """.trimIndent()
    }

    private fun loginHtml(): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>
        <title>随身下载盘</title>
        <style>body{font-family:sans-serif;background:#121418;color:#eee;margin:24px}input,button{font-size:16px;padding:8px}button{background:#FFB74D;border:0;border-radius:8px}</style>
        </head><body>
        <h1>需要密码</h1>
        <p>在手机 App「传到电脑」页能看到取文件密码。</p>
        <form method="get" action="/"><input name="k" type="password" placeholder="密码" autofocus/> <button>进入</button></form>
        </body></html>
    """.trimIndent()

    private fun queryParam(query: String, name: String): String? {
        if (query.isBlank()) return null
        return query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }?.ifBlank { null }
    }

    private fun cookieValue(headers: String, name: String): String? {
        val line = headers.lineSequence().firstOrNull { it.startsWith("Cookie:", true) } ?: return null
        return line.removePrefix("Cookie:").removePrefix("cookie:").split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
    }

    private fun writeText(
        out: OutputStream,
        code: Int,
        body: String,
        type: String = "text/plain; charset=utf-8",
        extra: String = "",
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            extra +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun writeFile(out: OutputStream, file: File, extra: String = "") {
        val encoded = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
        val header = "HTTP/1.1 200 OK\r\n" +
            extra +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$encoded\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        out.flush()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun human(value: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var n = value.toDouble()
        var i = 0
        while (n >= 1024 && i < units.lastIndex) {
            n /= 1024
            i++
        }
        return if (i == 0) "$value B" else String.format(Locale.US, "%.1f %s", n, units[i])
    }
}
