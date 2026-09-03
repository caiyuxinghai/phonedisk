package com.phonedisk.app.util

import android.content.Context

object Prefs {
    private const val NAME = "phonedisk"
    const val HOTSPOT_ASK = "ask"
    const val HOTSPOT_ALWAYS = "always"
    const val HOTSPOT_NEVER = "never"

    @Volatile
    var hotspotSessionAllowed: Boolean = false

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun wifiOnly(context: Context): Boolean = sp(context).getBoolean("wifiOnly", true)

    fun setWifiOnly(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("wifiOnly", value).apply()
    }

    fun speedLimitKBps(context: Context): Int = sp(context).getInt("speedLimitKBps", 0)

    fun setSpeedLimitKBps(context: Context, value: Int) {
        sp(context).edit().putInt("speedLimitKBps", value).apply()
    }

    fun speedLimitBps(context: Context): Long = speedLimitKBps(context) * 1024L

    fun hotspotPolicy(context: Context): String =
        sp(context).getString("hotspotPolicy", HOTSPOT_ASK) ?: HOTSPOT_ASK

    fun setHotspotPolicy(context: Context, value: String) {
        sp(context).edit().putString("hotspotPolicy", value).apply()
        if (value == HOTSPOT_ALWAYS) hotspotSessionAllowed = true
        if (value == HOTSPOT_NEVER) hotspotSessionAllowed = false
    }

    fun chargingOnly(context: Context): Boolean = sp(context).getBoolean("chargingOnly", false)

    fun setChargingOnly(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("chargingOnly", value).apply()
    }

    fun lanAuth(context: Context): Boolean = sp(context).getBoolean("lanAuth", true)

    fun setLanAuth(context: Context, value: Boolean) {
        sp(context).edit().putBoolean("lanAuth", value).apply()
    }

    fun lanToken(context: Context): String {
        val existing = sp(context).getString("lanToken", null)
        if (!existing.isNullOrBlank()) return existing
        val gen = (100000..999999).random().toString()
        sp(context).edit().putString("lanToken", gen).apply()
        return gen
    }

    fun setLanToken(context: Context, value: String) {
        sp(context).edit().putString("lanToken", value.filter { it.isLetterOrDigit() }.take(12)).apply()
    }
}
