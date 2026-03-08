package com.obd2monitor.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Central preferences store.
 * Handles saved BT device, watchdog timeout, and first-run flag.
 */
object AppPreferences {

    private const val PREFS = "obd2_prefs"

    // Keys
    private const val KEY_DEVICE_ADDRESS  = "last_bt_address"
    private const val KEY_DEVICE_NAME     = "last_bt_name"
    private const val KEY_SETUP_DONE      = "setup_done"
    private const val KEY_WATCHDOG_MIN    = "watchdog_minutes"
    private const val KEY_AUTO_CONNECT    = "auto_connect"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── BT Device ────────────────────────────────────────────────────

    fun saveDevice(ctx: Context, address: String, name: String) {
        prefs(ctx).edit()
            .putString(KEY_DEVICE_ADDRESS, address)
            .putString(KEY_DEVICE_NAME, name)
            .putBoolean(KEY_SETUP_DONE, true)
            .apply()
    }

    fun getSavedAddress(ctx: Context): String? =
        prefs(ctx).getString(KEY_DEVICE_ADDRESS, null)

    fun getSavedName(ctx: Context): String =
        prefs(ctx).getString(KEY_DEVICE_NAME, "OBD2") ?: "OBD2"

    fun isSetupDone(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SETUP_DONE, false)

    fun clearDevice(ctx: Context) {
        prefs(ctx).edit()
            .remove(KEY_DEVICE_ADDRESS)
            .remove(KEY_DEVICE_NAME)
            .putBoolean(KEY_SETUP_DONE, false)
            .apply()
    }

    // ── Watchdog ─────────────────────────────────────────────────────

    /** Minutes to wait before auto-shutdown when not connected. 0 = disabled */
    fun getWatchdogMinutes(ctx: Context): Int =
        prefs(ctx).getInt(KEY_WATCHDOG_MIN, 10)

    fun setWatchdogMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_WATCHDOG_MIN, minutes).apply()
    }

    // ── Auto connect ─────────────────────────────────────────────────

    fun isAutoConnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_CONNECT, true)

    fun setAutoConnect(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }
}
