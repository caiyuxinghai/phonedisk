package com.phonedisk.app.download

import android.content.Context
import com.phonedisk.app.data.TaskRepository
import com.phonedisk.app.data.TaskStatus
import com.phonedisk.app.util.AppLog
import com.phonedisk.app.util.Network
import com.phonedisk.app.util.Prefs

object DownloadResumer {
    suspend fun requeueIfReady(context: Context): Int {
        val repo = TaskRepository.get(context)
        val paused = repo.paused()
        var n = 0
        for (row in paused) {
            val ready = when {
                TaskStatus.waitingCharge(row) &&
                    (Network.isCharging(context) || !Prefs.chargingOnly(context)) -> true
                row.errorMessage?.contains("不是 Wi‑Fi") == true && Network.isMeteredOk(context) -> true
                else -> false
            }
            if (ready) {
                repo.update(row.copy(status = TaskStatus.QUEUED, errorMessage = null, speedBps = 0))
                n++
            }
        }
        if (n > 0) {
            AppLog.i("requeued $n paused task(s)")
            try {
                DownloadService.kick(context)
            } catch (e: Exception) {
                AppLog.e("kick after requeue failed", e)
            }
        }
        return n
    }
}
