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
import com.phonedisk.app.util.FileNames
import com.phonedisk.app.util.LinkGuard
import com.phonedisk.app.util.Network
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

    private var server: LanShareServer? = null

    fun addTask(rawUrl: String, customName: String?, wifiOnly: Boolean): String? {
        val url = LinkGuard.validate(rawUrl).getOrElse { return it.message }
        val userNamed = !customName.isNullOrBlank()
        val name = FileNames.sanitize(
            if (userNamed) customName!!.trim() else FileNames.fromUrl(url),
        )
        val dir = Storage.dir(getApplication())
        dir.mkdirs()
        val dest = FileNames.unique(dir, name)
        val entity = DownloadTaskEntity(
            url = url.toString(),
            fileName = dest.name,
            filePath = dest.absolutePath,
            status = TaskStatus.QUEUED,
            wifiOnly = wifiOnly,
            userNamed = userNamed,
        )
        viewModelScope.launch {
            repo.insert(entity)
            DownloadService.kick(getApplication())
        }
        return null
    }

    fun pause(id: Long) = DownloadService.pause(getApplication(), id)

    fun resume(id: Long) = DownloadService.resume(getApplication(), id)

    fun cancel(id: Long) = DownloadService.cancel(getApplication(), id)

    fun retry(id: Long) = DownloadService.resume(getApplication(), id)

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
