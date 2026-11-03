package com.jkbms.monitor

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("jk_bms_prefs", Context.MODE_PRIVATE)

    var targetMacAddress: String
        get() = prefs.getString("target_mac", "") ?: ""
        set(value) = prefs.edit().putString("target_mac", value).apply()

    var targetDeviceName: String
        get() = prefs.getString("target_name", "") ?: ""
        set(value) = prefs.edit().putString("target_name", value).apply()

    var alertsEnabled: Boolean
        get() = prefs.getBoolean("alerts_enabled", false)
        set(value) = prefs.edit().putBoolean("alerts_enabled", value).apply()

    var lowSocAlertThreshold: Int
        get() = prefs.getInt("low_soc_threshold", 20)
        set(value) = prefs.edit().putInt("low_soc_threshold", value).apply()

    var highTempAlertThreshold: Int
        get() = prefs.getInt("high_temp_threshold", 45)
        set(value) = prefs.edit().putInt("high_temp_threshold", value).apply()
}
