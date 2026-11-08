package com.jkbms.monitor

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("jk_bms_prefs", Context.MODE_PRIVATE)

    var targetMacAddress: String
        get() = prefs.getString("target_mac", "C8:47:80:55:04:85") ?: "C8:47:80:55:04:85"
        set(value) = prefs.edit().putString("target_mac", value.trim()).apply()

    var targetDeviceName: String
        get() = prefs.getString("target_name", "JK-B2A8S20P") ?: "JK-B2A8S20P"
        set(value) = prefs.edit().putString("target_name", value.trim()).apply()

    var esp32Host: String
        get() = prefs.getString("esp32_host", "192.168.123.105") ?: "192.168.123.105"
        set(value) = prefs.edit().putString("esp32_host", value.trim()).apply()

    var cloudTopicId: String
        get() = prefs.getString("cloud_topic_id", "c84780550485") ?: "c84780550485"
        set(value) = prefs.edit().putString("cloud_topic_id", value.trim()).apply()

    var controlPin: String
        get() = prefs.getString("control_pin", "1234") ?: "1234"
        set(value) = prefs.edit().putString("control_pin", value.trim()).apply()

    // Новый ключ v22 гарантирует сброс случайно включенного прямого BLE, чтобы телефон не мешал плате ESP32
    var useDirectBle: Boolean
        get() = prefs.getBoolean("use_direct_ble_v22", false)
        set(value) = prefs.edit().putBoolean("use_direct_ble_v22", value).apply()

    var shadeNotificationEnabled: Boolean
        get() = prefs.getBoolean("shade_notif_enabled", true)
        set(value) = prefs.edit().putBoolean("shade_notif_enabled", value).apply()

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
