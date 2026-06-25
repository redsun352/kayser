package com.arkeosar.groundscan.data

import android.content.Context

/**
 * Persisted user-configurable scan settings, backed by SharedPreferences.
 * Independent, minimal implementation for ArkeoSAR Ground Scan.
 */
class SettingsData(context: Context) {

    private val prefs = context.getSharedPreferences("arkeosar_groundscan_settings", Context.MODE_PRIVATE)

    var areaMeters: Int
        get() = prefs.getInt(KEY_AREA_METERS, 5)
        set(value) = prefs.edit().putInt(KEY_AREA_METERS, value).apply()

    var automaticMode: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_MODE, value).apply()

    var zigzag: Boolean
        get() = prefs.getBoolean(KEY_ZIGZAG, true)
        set(value) = prefs.edit().putBoolean(KEY_ZIGZAG, value).apply()

    companion object {
        private const val KEY_AREA_METERS = "area_meters"
        private const val KEY_AUTO_MODE = "auto_mode"
        private const val KEY_ZIGZAG = "zigzag"
    }
}
