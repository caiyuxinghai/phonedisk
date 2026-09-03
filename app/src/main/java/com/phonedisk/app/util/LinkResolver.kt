package com.phonedisk.app.util

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

class MemoryCookieJar : CookieJar {
    private val lock = Any()
    private val stored = mutableListOf<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            cookies.forEach { cookie ->
                stored.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                stored += cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            stored.removeAll { it.expiresAt < now }
            return stored.filter { it.matches(url) }
        }
    }
}

object HttpClients {
    val cookies = MemoryCookieJar()
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(cookies)
        .build()
}

data class ResolvedLink(
    val url: String,
    val referer: String? = null,
    val suggestedName: String? = null,
)

class HtmlPageException(val html: String, val pageUrl: String) :
    Exception("服务器返回了网页而不是文件。链接可能未公开、需要登录，或不是直接文件。")

object LinkResolver {
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    fun splitUrls(raw: String): List<String> {
        return raw
            .split('\n', '\r', '\t', ' ', ',', ';')
            .map { it.trim().trim('"').trim('\'') }
            .filter { it.isNotEmpty() }
    }

    fun rewrite(rawUrl: String): ResolvedLink {
        val url = LinkGuard.validate(rawUrl).getOrThrow()
        val host = url.host.lowercase(Locale.US)

        when {
            host.contains("dropbox.com") -> {
                val next = url.newBuilder()
                    .setQueryParameter("dl", "1")
                    .removeAllQueryParameters("raw")
                    .build()
                return ResolvedLink(next.toString(), rawUrl, FileNames.fromUrl(url))
            }
            host.contains("drive.google.com") || host.contains("docs.google.com") ||
                host.contains("drive.usercontent.google.com") -> {
                return rewriteGoogle(url, rawUrl)
            }
            host == "1drv.ms" || host.contains("onedrive.live.com") ||
                host.contains("sharepoint.com") -> {
                return rewriteOneDrive(url)
            }
            host == "github.com" || host == "www.github.com" -> {
                return rewriteGitHub(url)
            }
            host.contains("githubusercontent.com") -> {
                return ResolvedLink(url.toString(), rawUrl, FileNames.fromUrl(url))
            }
            host.contains("huggingface.co") -> {
                return rewriteHuggingFace(url)
            }
            else -> return ResolvedLink(url.toString(), rawUrl, FileNames.fromUrl(url))
        }
    }

    fun fromHtml(pageUrl: String, html: String): ResolvedLink? {
        val current = pageUrl.toHttpUrlOrNull() ?: return null
        val host = current.host.lowercase(Locale.US)
        if (host.contains("drive.google.com") || host.contains("docs.google.com") ||
            host.contains("drive.usercontent.google.com")
        ) {
            val id = googleFileId(current) ?: Regex("[?&]id=([\\w-]+)").find(html)?.groupValues?.get(1)
            val confirm = Regex("confirm=([0-9A-Za-z_-]{4,})").find(html)?.groupValues?.get(1)
                ?: Regex("name=\"confirm\"\\s+value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                ?: "t"
            if (id != null) {
                val next = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=$confirm"
                return ResolvedLink(next, pageUrl, "gdrive-$id.bin")
            }
        }
        if (host.contains("mediafire.com")) {
            val href = Regex(
                """href="(https://download[^"]*mediafire\.com/[^"]+)"""",
                RegexOption.IGNORE_CASE,
            ).find(html)?.groupValues?.get(1)
                ?: Regex(
                    """'(https://download[^']*mediafire\.com/[^']+)'""",
                    RegexOption.IGNORE_CASE,
                ).find(html)?.groupValues?.get(1)
            if (href != null) {
                return ResolvedLink(href, pageUrl, FileNames.fromUrl(current))
            }
        }
        val sourceforge = Regex(
            """href="(https://[^"]+sourceforge\.net/[^"]+\.(?:iso|zip|7z|exe|msi|gz|xz|dmg)[^"]*)"""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
        if (host.contains("sourceforge.net") && sourceforge != null) {
            return ResolvedLink(sourceforge, pageUrl, null)
        }
        return null
    }

    private fun rewriteGoogle(url: HttpUrl, raw: String): ResolvedLink {
        val path = url.encodedPath
        val id = googleFileId(url)
        when {
            path.contains("/document/d/") && id != null -> {
                val next = "https://docs.google.com/document/d/$id/export?format=pdf"
                return ResolvedLink(next, raw, "$id.pdf")
            }
            path.contains("/spreadsheets/d/") && id != null -> {
                val next = "https://docs.google.com/spreadsheets/d/$id/export?format=xlsx"
                return ResolvedLink(next, raw, "$id.xlsx")
            }
            path.contains("/presentation/d/") && id != null -> {
                val next = "https://docs.google.com/presentation/d/$id/export?format=pdf"
                return ResolvedLink(next, raw, "$id.pdf")
            }
            id != null -> {
                val next = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
                return ResolvedLink(next, raw, "gdrive-$id.bin")
            }
            else -> return ResolvedLink(url.toString(), raw, FileNames.fromUrl(url))
        }
    }

    private fun googleFileId(url: HttpUrl): String? {
        url.queryParameter("id")?.let { if (it.isNotBlank()) return it }
        val segs = url.pathSegments.filter { it.isNotBlank() }
        val dIndex = segs.indexOf("d")
        if (dIndex >= 0 && dIndex + 1 < segs.size) return segs[dIndex + 1]
        val fileIndex = segs.indexOf("file")
        if (fileIndex >= 0 && fileIndex + 2 < segs.size && segs[fileIndex + 1] == "d") {
            return segs[fileIndex + 2]
        }
        return null
    }

    private fun rewriteOneDrive(url: HttpUrl): ResolvedLink {
        val original = url.toString()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(original.toByteArray(Charsets.UTF_8))
        val next = "https://api.onedrive.com/v1.0/shares/u!$encoded/root/content"
        return ResolvedLink(next, original, FileNames.fromUrl(url).ifBlank { "onedrive.bin" })
    }

    private fun rewriteGitHub(url: HttpUrl): ResolvedLink {
        val segs = url.pathSegments.filter { it.isNotBlank() }
        if (segs.size >= 5 && segs[2] == "blob") {
            val user = segs[0]
            val repo = segs[1]
            val rest = segs.drop(3).joinToString("/")
            val next = "https://raw.githubusercontent.com/$user/$repo/$rest"
            return ResolvedLink(next, url.toString(), segs.last())
        }
        if (segs.size >= 5 && segs[2] == "raw") {
            val user = segs[0]
            val repo = segs[1]
            val rest = segs.drop(3).joinToString("/")
            val next = "https://raw.githubusercontent.com/$user/$repo/$rest"
            return ResolvedLink(next, url.toString(), segs.last())
        }
        if (segs.size >= 4 && segs.getOrNull(2) == "releases" && segs.getOrNull(3) == "tag") {
            throw IllegalArgumentException("这是 GitHub 版本说明页。请打开 Assets，复制具体文件的下载地址。")
        }
        return ResolvedLink(url.toString(), url.toString(), FileNames.fromUrl(url))
    }

    private fun rewriteHuggingFace(url: HttpUrl): ResolvedLink {
        val path = url.encodedPath
        val next = if (path.contains("/blob/")) {
            url.newBuilder().encodedPath(path.replaceFirst("/blob/", "/resolve/")).build().toString()
        } else {
            url.toString()
        }
        return ResolvedLink(next, url.toString(), FileNames.fromUrl(url))
    }
}
