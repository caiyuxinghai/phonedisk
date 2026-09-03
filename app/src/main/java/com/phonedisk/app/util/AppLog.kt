package com.phonedisk.app.util

import android.util.Log

object AppLog {
    private const val TAG = "PhoneDisk"
    private const val MAX = 50
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun i(msg: String) {
        Log.i(TAG, msg)
        lines.addLast(msg)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun e(msg: String, error: Throwable? = null) {
        Log.e(TAG, msg, error)
        val extra = error?.message?.let { " ($it)" } ?: ""
        lines.addLast("ERR $msg$extra")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = if (lines.isEmpty()) "还没有下载记录" else lines.joinToString("\n")
}
