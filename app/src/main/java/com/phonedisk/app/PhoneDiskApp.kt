package com.phonedisk.app

import android.app.Application
import com.phonedisk.app.data.TaskRepository
import com.phonedisk.app.download.DownloadService
import com.phonedisk.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneDiskApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            val repo = TaskRepository.get(this@PhoneDiskApp)
            repo.recoverInterrupted()
            if (repo.nextQueued() != null) {
                try {
                    DownloadService.kick(this@PhoneDiskApp)
                    AppLog.i("resumed queued downloads")
                } catch (e: Exception) {
                    AppLog.e("could not auto-start download service", e)
                }
            }
        }
    }
}
