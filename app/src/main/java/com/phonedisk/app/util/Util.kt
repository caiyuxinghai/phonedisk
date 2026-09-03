package com.phonedisk.app.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.net.NetworkInterface
import java.net.URLDecoder
import java.util.Locale

object LinkGuard {
    fun validate(raw: String): Result<HttpUrl> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("请粘贴下载链接。"))
        }
        if (trimmed.startsWith("magnet:", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("不支持磁力链接。请粘贴浏览器能直接点下去的 http(s) 文件地址。"))
        }
        val withScheme =
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        val url = withScheme.toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("链接无效，请粘贴完整的 http(s) 地址。"))
        val host = url.host.lowercase(Locale.US)
        if (host == "store.steampowered.com" ||
            host == "steamcommunity.com" ||
            host.endsWith(".steampowered.com")
        ) {
            return Result.failure(
                IllegalArgumentException(
                    "这是 Steam 页面，不是文件直链。Steam 游戏必须用电脑上的 Steam 客户端下载，无法通过链接下到手机。",
                ),
            )
        }
        if (host.contains("epicgames.com") && url.encodedPath.contains("/store")) {
            return Result.failure(
                IllegalArgumentException("Epic 商店页不是文件直链，无法下载游戏库里的游戏。"),
            )
        }
        return Result.success(url)
    }
}

object FileNames {
    fun sanitize(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .trim('.')
        val cut = if (cleaned.length > 180) cleaned.take(180) else cleaned
        return cut.ifBlank { "download.bin" }
    }

    fun fromUrl(url: HttpUrl): String {
        val last = url.pathSegments.lastOrNull { it.isNotBlank() } ?: "download.bin"
        val decoded = try {
            URLDecoder.decode(last, "UTF-8")
        } catch (_: Exception) {
            last
        }
        return sanitize(decoded)
    }

    fun fromDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val star = Regex(
            "filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')([^;]+)",
            RegexOption.IGNORE_CASE,
        ).find(header)
        if (star != null) {
            return try {
                sanitize(URLDecoder.decode(star.groupValues[1].trim().trim('"'), "UTF-8"))
            } catch (_: Exception) {
                sanitize(star.groupValues[1].trim().trim('"'))
            }
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)
        return plain?.groupValues?.get(1)?.let { sanitize(it.trim()) }
    }

    fun unique(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists() && !partFile(base).exists()) return base
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists() && !partFile(candidate).exists()) return candidate
            i++
        }
    }

    fun partFile(finalFile: File): File = File(finalFile.parentFile, finalFile.name + ".part")
}

object Format {
    fun bytes(value: Long): String {
        if (value < 0) return "未知"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var n = value.toDouble()
        var i = 0
        while (n >= 1024 && i < units.lastIndex) {
            n /= 1024
            i++
        }
        return if (i == 0) "$value B" else String.format(Locale.US, "%.1f %s", n, units[i])
    }

    fun speed(bps: Long): String {
        if (bps <= 0) return "0 B/s"
        return bytes(bps) + "/s"
    }

    fun percent(downloaded: Long, total: Long): Float {
        if (total <= 0) return 0f
        return (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    fun percentLabel(downloaded: Long, total: Long): String {
        if (total <= 0) return ""
        return "${((downloaded * 100) / total).coerceIn(0, 100)}%"
    }

    fun eta(downloaded: Long, total: Long, bps: Long): String {
        if (total <= 0 || bps <= 0 || downloaded >= total) return ""
        val sec = ((total - downloaded) / bps).coerceAtLeast(0)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 48 -> "剩余 ${h / 24} 天"
            h > 0 -> "剩余 ${h}小时${m}分"
            m > 0 -> "剩余 ${m}分${s}秒"
            else -> "剩余 ${s}秒"
        }
    }
}

object Storage {
    fun dir(context: Context): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PhoneDisk",
        )
        return if (canWritePublic()) {
            publicDir.mkdirs()
            publicDir
        } else {
            val fallback = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "PhoneDisk",
            )
            fallback.mkdirs()
            fallback
        }
    }

    fun canWritePublic(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun usingPublicFolder(context: Context): Boolean {
        return dir(context).absolutePath.contains(
            "${File.separator}Download${File.separator}PhoneDisk",
        )
    }

    const val LOW_BYTES = 2L * 1024 * 1024 * 1024
    const val CRITICAL_BYTES = 500L * 1024 * 1024
    const val MIN_FREE_TO_START = 200L * 1024 * 1024

    fun marginFor(needed: Long): Long = maxOf(200L * 1024 * 1024, needed / 50)

    fun hasSpace(dir: File, needed: Long): Boolean {
        if (needed <= 0) return true
        val avail = availableBytes(dir)
        if (avail < 0) return true
        return avail > needed + marginFor(needed)
    }

    fun availableBytes(dir: File): Long {
        return try {
            if (!dir.exists()) dir.mkdirs()
            StatFs(dir.absolutePath).availableBytes
        } catch (_: Exception) {
            -1
        }
    }

    fun notEnoughMessage(fileSize: Long, stillNeed: Long, available: Long): String {
        return "空间不足：文件约 ${Format.bytes(fileSize)}，还需要约 ${Format.bytes(stillNeed)}，手机只剩 ${Format.bytes(available)}。请删文件或先拷到电脑后再继续。"
    }

    fun isNoSpace(error: Throwable?): Boolean {
        var t = error
        while (t != null) {
            if (t is android.system.ErrnoException && t.errno == android.system.OsConstants.ENOSPC) {
                return true
            }
            val msg = t.message.orEmpty().lowercase()
            if (msg.contains("enospc") || msg.contains("no space") || msg.contains("空间不足") ||
                msg.contains("空间写满")
            ) {
                return true
            }
            t = t.cause
        }
        return false
    }
}

object Network {
    fun isMeteredOk(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isCharging(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        if (bm.isCharging) return true
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    fun isLikelyHotspot(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (cm.isActiveNetworkMetered) return true
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        val gateways = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29) {
            val lp = cm.getLinkProperties(net)
            lp?.routes?.forEach { route ->
                if (route.isDefaultRoute) {
                    route.gateway?.hostAddress?.let { gateways += it.substringBefore('%') }
                }
            }
        } else {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val g = wm.dhcpInfo?.gateway ?: 0
            if (g != 0) {
                gateways += "${g and 0xff}.${g shr 8 and 0xff}.${g shr 16 and 0xff}.${g shr 24 and 0xff}"
            }
        }
        return gateways.any { ip ->
            ip.startsWith("192.168.43.") ||
                ip.startsWith("172.20.10.") ||
                ip.startsWith("192.168.137.") ||
                ip.startsWith("192.168.42.") ||
                ip.startsWith("192.168.75.") ||
                ip.startsWith("192.168.44.") ||
                ip.startsWith("192.168.49.") ||
                ip.startsWith("192.168.107.")
        }
    }

    fun localIpv4(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val private = mutableListOf<String>()
        val other = mutableListOf<String>()
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (addr.isLoopbackAddress || addr.hostAddress.isNullOrBlank()) continue
                val host = addr.hostAddress ?: continue
                if (host.contains(':')) continue
                if (host.startsWith("10.") ||
                    host.startsWith("192.168.") ||
                    host.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
                ) {
                    private += host
                } else {
                    other += host
                }
            }
        }
        return private.firstOrNull() ?: other.firstOrNull()
    }
}
