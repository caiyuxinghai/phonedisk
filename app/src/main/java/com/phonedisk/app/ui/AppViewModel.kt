package com.phonedisk.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonedisk.app.data.DownloadTaskEntity
import com.phonedisk.app.data.TaskRepository
import com.phonedisk.app.data.TaskStatus
import com.phonedisk.app.download.DownloadService
import com.phonedisk.app.share.LanShareServer
import com.phonedisk.app.util.AppLog
import com.phonedisk.app.util.FileNames
import com.phonedisk.app.util.Format
import com.phonedisk.app.util.LinkGuard
import com.phonedisk.app.util.LinkResolver
import com.phonedisk.app.util.Network
import com.phonedisk.app.util.Prefs
import com.phonedisk.app.util.Storage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = TaskRepository.get(app)

    val tasks = repo.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    var shareOn by mutableStateOf(false)
        private set
    var shareUrl by mutableStateOf<String?>(null)
        private set
    var shareError by mutableStateOf<String?>(null)
        private set
    var incomingDraft by mutableStateOf<String?>(null)
    var speedLimitKBps by mutableStateOf(Prefs.speedLimitKBps(app))

    private var server: LanShareServer? = null

    fun acceptIntent(intent: Intent?) {
        if (intent == null) return
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.data?.toString()
            else -> null
        }?.trim().orEmpty()
        if (text.startsWith("http://") || text.startsWith("https://")) {
            incomingDraft = text
        }
    }

    fun clearIncoming() {
        incomingDraft = null
    }

    fun addTask(rawUrl: String, customName: String?, wifiOnly: Boolean): String? {
        val parts = LinkResolver.splitUrls(rawUrl)
        if (parts.isEmpty()) return "请粘贴下载链接。"
        val errors = mutableListOf<String>()
        val entities = mutableListOf<DownloadTaskEntity>()
        for (part in parts) {
            when (val built = buildEntity(part, if (parts.size == 1) customName else null, wifiOnly)) {
                is Built.Ok -> entities += built.entity
                is Built.Err -> errors += built.message
            }
        }
        if (entities.isNotEmpty()) {
            viewModelScope.launch {
                entities.forEach { repo.insert(it) }
                AppLog.i("queued ${entities.size} task(s)")
                DownloadService.kick(getApplication())
            }
        }
        return when {
            errors.isEmpty() -> null
            entities.isEmpty() -> errors.joinToString("\n")
            else -> "已添加 ${entities.size} 个，失败：\n${errors.joinToString("\n")}"
        }
    }

    private sealed class Built {
        data class Ok(val entity: DownloadTaskEntity) : Built()
        data class Err(val message: String) : Built()
    }

    private fun buildEntity(rawUrl: String, customName: String?, wifiOnly: Boolean): Built {
        val parsed = LinkGuard.validate(rawUrl).getOrElse { return Built.Err(it.message ?: "链接无效") }
        val rewritten = try {
            LinkResolver.rewrite(rawUrl)
        } catch (e: Exception) {
            return Built.Err(e.message ?: "链接无法解析")
        }
        val userNamed = !customName.isNullOrBlank()
        val guessed = rewritten.suggestedName ?: FileNames.fromUrl(parsed)
        val name = FileNames.sanitize(if (userNamed) customName!!.trim() else guessed)
        val dir = Storage.dir(getApplication())
        dir.mkdirs()
        val free = Storage.availableBytes(dir)
        if (free in 0 until Storage.MIN_FREE_TO_START) {
            return Built.Err(
                "手机剩余空间只有 ${Format.bytes(free)}，不足 200 MB。请先把已下文件拷到电脑或删掉一些。",
            )
        }
        val dest = FileNames.unique(dir, name)
        return Built.Ok(
            DownloadTaskEntity(
                url = parsed.toString(),
                fileName = dest.name,
                filePath = dest.absolutePath,
                status = TaskStatus.QUEUED,
                wifiOnly = wifiOnly,
                userNamed = userNamed,
            ),
        )
    }

    fun pause(id: Long) = DownloadService.pause(getApplication(), id)

    fun resume(id: Long) = DownloadService.resume(getApplication(), id)

    fun cancel(id: Long) = DownloadService.cancel(getApplication(), id)

    fun retry(id: Long) {
        viewModelScope.launch {
            val row = repo.get(id)
            if (row != null && TaskStatus.waitingHotspot(row)) {
                Prefs.hotspotSessionAllowed = true
            }
            DownloadService.resume(getApplication(), id)
        }
    }

    fun confirmHotspot(always: Boolean = false) {
        val app = getApplication<Application>()
        if (always) Prefs.setHotspotPolicy(app, Prefs.HOTSPOT_ALWAYS)
        Prefs.hotspotSessionAllowed = true
        viewModelScope.launch {
            tasks.value.filter { TaskStatus.waitingHotspot(it) }.forEach { row ->
                repo.update(row.copy(status = TaskStatus.QUEUED, errorMessage = null, speedBps = 0))
            }
            DownloadService.kick(app)
        }
    }

    fun denyHotspot() {
        viewModelScope.launch {
            tasks.value.filter { TaskStatus.waitingHotspot(it) }.forEach { row ->
                repo.update(
                    row.copy(
                        errorMessage = "因手机热点已暂停，避免消耗流量。连上普通 Wi‑Fi 后点继续。",
                        speedBps = 0,
                    ),
                )
            }
        }
    }

    fun setSpeedLimit(kbps: Int) {
        Prefs.setSpeedLimitKBps(getApplication(), kbps)
        speedLimitKBps = kbps
    }

    fun deleteCompleted() {
        viewModelScope.launch {
            tasks.value.filter { it.status == TaskStatus.COMPLETED }.forEach { task ->
                FileNames.partFile(File(task.filePath)).delete()
                File(task.filePath).delete()
                repo.delete(task)
            }
        }
    }

    fun deleteTask(task: DownloadTaskEntity) {
        viewModelScope.launch {
            FileNames.partFile(File(task.filePath)).delete()
            File(task.filePath).delete()
            repo.delete(task)
        }
    }

    fun shareFile(task: DownloadTaskEntity): Intent? {
        val file = File(task.filePath)
        if (!file.isFile) return null
        val uri = FileProvider.getUriForFile(
            getApplication(),
            "com.phonedisk.app.files",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun saveFolder(): File = Storage.dir(getApplication())

    fun usingPublicFolder(): Boolean = Storage.usingPublicFolder(getApplication())

    fun canWritePublic(): Boolean = Storage.canWritePublic()

    fun toggleShare(on: Boolean) {
        if (on) startShare() else stopShare()
    }

    private fun startShare() {
        shareError = null
        val ip = Network.localIpv4()
        if (ip == null) {
            shareOn = false
            shareUrl = null
            shareError = "找不到局域网 IP。请让手机连上 Wi‑Fi 后再开。"
            return
        }
        try {
            val next = LanShareServer(saveFolder())
            next.start()
            server = next
            shareOn = true
            shareUrl = "http://$ip:${next.port}"
        } catch (e: Exception) {
            shareOn = false
            shareUrl = null
            shareError = e.message ?: "无法开启局域网服务"
        }
    }

    private fun stopShare() {
        server?.stop()
        server = null
        shareOn = false
        shareUrl = null
    }

    override fun onCleared() {
        stopShare()
        super.onCleared()
    }
}
