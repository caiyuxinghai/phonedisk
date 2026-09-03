package com.phonedisk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phonedisk.app.download.DownloadResumer
import kotlinx.coroutines.runBlocking

class PowerConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    DownloadResumer.requeueIfReady(context.applicationContext)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
