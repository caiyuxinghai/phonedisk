package com.phonedisk.app.util

import android.content.Context

object Prefs {
    private const val NAME = "phonedisk"

    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean("wifiOnly", true)

    fun setWifiOnly(context: Context, value: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("wifiOnly", value)
            .apply()
    }
}
