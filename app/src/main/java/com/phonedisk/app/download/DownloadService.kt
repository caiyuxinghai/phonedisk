package com.phonedisk.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phonedisk.app.MainActivity
import com.phonedisk.app.R
import com.phonedisk.app.data.TaskRepository
import com.phonedisk.app.data.TaskStatus
import com.phonedisk.app.util.AppLog
import com.phonedisk.app.util.FileNames
import com.phonedisk.app.util.Format
import com.phonedisk.app.util.HtmlPageException
import com.phonedisk.app.util.LinkResolver
import com.phonedisk.app.util.Network
import com.phonedisk.app.util.Prefs
import com.phonedisk.app.util.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engine = DownloadEngine()
    private val pauseFlag = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private val currentId = AtomicLong(-1)
    private val loopRunning = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startFg(placeholder("准备下载…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (id == currentId.get()) pauseFlag.set(true)
                else scope.launch { mark(id, TaskStatus.PAUSED, null) }
            }
            ACTION_CANCEL -> {
                if (id == currentId.get()) cancelFlag.set(true)
                else scope.launch { cancelStored(id) }
            }
            ACTION_RESUME -> scope.launch { resumeStored(id) }
        }
        if (loopRunning.compareAndSet(false, true)) {
            scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopRunning.set(false)
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun loop() {
        val repo = TaskRepository.get(this)
        try {
            while (scope.isActive) {
                val task = repo.nextQueued()
                if (task == null) {
                    delay(400)
                    if (repo.nextQueued() == null) break
                    continue
                }
                currentId.set(task.id)
                pauseFlag.set(false)
                cancelFlag.set(false)

                if (task.wifiOnly && !Network.isMeteredOk(this)) {
                    repo.update(
                        task.copy(
                            status = TaskStatus.PAUSED,
                            errorMessage = "当前不是 Wi‑Fi。连上后点继续。",
                            speedBps = 0,
                        ),
                    )
                    continue
                }

                if (Network.isLikelyHotspot(this)) {
                    when (Prefs.hotspotPolicy(this)) {
                        Prefs.HOTSPOT_NEVER -> {
                            repo.update(
                                task.copy(
                                    status = TaskStatus.PAUSED,
                                    errorMessage = "已禁止在手机热点上下载。连上普通 Wi‑Fi 后点继续。",
                                    speedBps = 0,
                                ),
                            )
                            continue
                        }
                        Prefs.HOTSPOT_ALWAYS, Prefs.HOTSPOT_ASK -> {
                            if (Prefs.hotspotPolicy(this) == Prefs.HOTSPOT_ALWAYS || Prefs.hotspotSessionAllowed) {
                                // proceed
                            } else {
                                repo.update(
                                    task.copy(
                                        status = TaskStatus.PAUSED,
                                        errorMessage = TaskStatus.MSG_HOTSPOT,
                                        speedBps = 0,
                                    ),
                                )
                                notifyHotspot()
                                continue
                            }
                        }
                    }
                }

                val dest = File(task.filePath)
                dest.parentFile?.mkdirs()
                acquireWakeLock()
                try {
                    val resolved = try {
                        LinkResolver.rewrite(task.url)
                    } catch (e: Exception) {
                        throw IllegalStateException(e.message ?: "链接无法解析")
                    }
                    AppLog.i("task ${task.id} rewrite -> ${resolved.url.take(120)}")
                    val part = FileNames.partFile(dest)
                    val probed = engine.probeSize(resolved.url, resolved.referer)
                    AppLog.i("task ${task.id} probeSize=$probed free=${Storage.availableBytes(dest.parentFile ?: dest)}")
                    val knownSize = if (probed > 0) probed else task.totalBytes
                    if (knownSize > 0) {
                        val stillNeed = (knownSize - part.length().coerceAtLeast(0)).coerceAtLeast(0)
                        val avail = Storage.availableBytes(dest.parentFile ?: dest)
                        if (!Storage.hasSpace(dest.parentFile ?: dest, stillNeed)) {
                            val msg = Storage.notEnoughMessage(knownSize, stillNeed, avail)
                            AppLog.e("task ${task.id} $msg")
                            repo.update(
                                task.copy(
                                    status = TaskStatus.FAILED,
                                    totalBytes = knownSize,
                                    errorMessage = msg,
                                    speedBps = 0,
                                ),
                            )
                            continue
                        }
                    }
                    repo.update(
                        task.copy(
                            status = TaskStatus.RUNNING,
                            totalBytes = if (knownSize > 0) knownSize else task.totalBytes,
                            errorMessage = null,
                            speedBps = 0,
                        ),
                    )
                    startFg(progressNotification(task.fileName, task.downloadedBytes, if (knownSize > 0) knownSize else task.totalBytes, 0))
                    var lastName: String? = null
                    var lastUi = 0L
                    val sink: (com.phonedisk.app.download.DownloadEngine.Progress) -> Unit = { p ->
                        if (!p.fileName.isNullOrBlank()) lastName = p.fileName
                        val now = System.currentTimeMillis()
                        if (now - lastUi >= 1000L) {
                            lastUi = now
                            runBlocking {
                                val latest = repo.get(task.id) ?: return@runBlocking
                                repo.update(
                                    latest.copy(
                                        downloadedBytes = p.downloaded,
                                        totalBytes = if (p.total > 0) p.total else latest.totalBytes,
                                        speedBps = p.speedBps,
                                        status = TaskStatus.RUNNING,
                                    ),
                                )
                            }
                            startFg(progressNotification(task.fileName, p.downloaded, p.total, p.speedBps))
                        }
                    }
                    try {
                        engine.download(
                            url = resolved.url,
                            finalFile = dest,
                            pauseFlag = pauseFlag,
                            cancelFlag = cancelFlag,
                            referer = resolved.referer,
                            speedLimitBps = { Prefs.speedLimitBps(this) },
                            onProgress = sink,
                        )
                    } catch (html: HtmlPageException) {
                        AppLog.i("task ${task.id} html from ${html.pageUrl.take(120)}")
                        val next = LinkResolver.fromHtml(html.pageUrl, html.html)
                            ?: throw IllegalStateException("这不是可下载的文件。分享页若未公开，或需要登录，就无法下。")
                        AppLog.i("task ${task.id} html extract -> ${next.url.take(120)}")
                        val probed2 = engine.probeSize(next.url, next.referer ?: html.pageUrl)
                        if (probed2 > 0) {
                            val stillNeed = (probed2 - FileNames.partFile(dest).length().coerceAtLeast(0)).coerceAtLeast(0)
                            val avail = Storage.availableBytes(dest.parentFile ?: dest)
                            if (!Storage.hasSpace(dest.parentFile ?: dest, stillNeed)) {
                                throw IllegalStateException(Storage.notEnoughMessage(probed2, stillNeed, avail))
                            }
                        }
                        engine.download(
                            url = next.url,
                            finalFile = dest,
                            pauseFlag = pauseFlag,
                            cancelFlag = cancelFlag,
                            referer = next.referer ?: html.pageUrl,
                            speedLimitBps = { Prefs.speedLimitBps(this) },
                            onProgress = sink,
                        )
                    }
                    val done = repo.get(task.id)
                    if (done != null) {
                        var finalPath = dest
                        val hinted = lastName
                        if (!done.userNamed && !hinted.isNullOrBlank() && hinted != dest.name && dest.exists()) {
                            val renamed = FileNames.unique(dest.parentFile ?: dest, hinted)
                            if (dest.renameTo(renamed)) finalPath = renamed
                        }
                        val size = if (finalPath.exists()) finalPath.length() else done.downloadedBytes
                        repo.update(
                            done.copy(
                                status = TaskStatus.COMPLETED,
                                fileName = finalPath.name,
                                filePath = finalPath.absolutePath,
                                downloadedBytes = size,
                                totalBytes = size,
                                speedBps = 0,
                                errorMessage = null,
                                completedAt = System.currentTimeMillis(),
                            ),
                        )
                        notifyDone(task.id, finalPath.name, size)
                    }
                } catch (_: DownloadEngine.Paused) {
                    val latest = repo.get(task.id) ?: continue
                    repo.update(latest.copy(status = TaskStatus.PAUSED, speedBps = 0))
                } catch (_: DownloadEngine.Canceled) {
                    cancelStored(task.id)
                } catch (_: DownloadEngine.NoSpace) {
                    val latest = repo.get(task.id) ?: continue
                    val avail = Storage.availableBytes(dest.parentFile ?: dest)
                    repo.update(
                        latest.copy(
                            status = TaskStatus.PAUSED,
                            speedBps = 0,
                            errorMessage = "下载中途空间写满，手机只剩 ${Format.bytes(avail)}。清理或拷走文件后点继续。",
                        ),
                    )
                } catch (e: Exception) {
                    val latest = repo.get(task.id) ?: continue
                    if (Storage.isNoSpace(e)) {
                        val avail = Storage.availableBytes(dest.parentFile ?: dest)
                        repo.update(
                            latest.copy(
                                status = TaskStatus.PAUSED,
                                speedBps = 0,
                                errorMessage = "下载中途空间写满，手机只剩 ${Format.bytes(avail)}。清理或拷走文件后点继续。",
                            ),
                        )
                    } else {
                        AppLog.e("task ${task.id} failed", e)
                        repo.update(
                            latest.copy(
                                status = TaskStatus.FAILED,
                                speedBps = 0,
                                errorMessage = e.message ?: "下载失败",
                            ),
                        )
                    }
                } finally {
                    releaseWakeLock()
                    currentId.set(-1)
                }
            }
        } finally {
            loopRunning.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun mark(id: Long, status: String, error: String?) {
        if (id <= 0) return
        val repo = TaskRepository.get(this)
        val row = repo.get(id) ?: return
        repo.update(row.copy(status = status, speedBps = 0, errorMessage = error))
    }

    private suspend fun resumeStored(id: Long) {
        if (id <= 0) return
        val repo = TaskRepository.get(this)
        val row = repo.get(id) ?: return
        if (row.status == TaskStatus.COMPLETED) return
        repo.update(row.copy(status = TaskStatus.QUEUED, errorMessage = null, speedBps = 0))
    }

    private suspend fun cancelStored(id: Long) {
        if (id <= 0) return
        val repo = TaskRepository.get(this)
        val row = repo.get(id) ?: return
        FileNames.partFile(File(row.filePath)).delete()
        if (row.status != TaskStatus.COMPLETED) {
            File(row.filePath).delete()
        }
        repo.update(
            row.copy(
                status = TaskStatus.CANCELED,
                speedBps = 0,
                errorMessage = null,
            ),
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "下载进度", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "完成与提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    private fun notifyDone(id: Long, name: String, size: Long) {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("下载完成")
            .setContentText("$name · ${Format.bytes(size)}")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify((10000 + (id % 10000)).toInt(), n)
    }

    private fun notifyHotspot() {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("正在使用手机热点")
            .setContentText("继续下载会消耗大量流量，点开 App 确认是否继续。")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_HOTSPOT, n)
    }

    private fun startFg(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun placeholder(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun progressNotification(name: String, downloaded: Long, total: Long, speed: Long): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(name)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            val eta = Format.eta(downloaded, total, speed)
            val extra = if (eta.isNotEmpty()) " · $eta" else ""
            builder.setContentText("${Format.bytes(downloaded)} / ${Format.bytes(total)} · ${Format.speed(speed)}$extra")
            builder.setProgress(100, pct, false)
        } else {
            builder.setContentText("${Format.bytes(downloaded)} · ${Format.speed(speed)}")
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonedisk:dl").also {
            it.setReferenceCounted(false)
            it.acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    companion object {
        const val ACTION_KICK = "kick"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_CANCEL = "cancel"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "downloads"
        private const val CHANNEL_ALERT = "alerts"
        private const val NOTIF_ID = 41
        private const val NOTIF_HOTSPOT = 42

        fun kick(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_KICK)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context, id: Long) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun resume(context: Context, id: Long) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_RESUME)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, id: Long) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
