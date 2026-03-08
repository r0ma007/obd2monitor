package com.obd2monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts OBD2Service automatically after device reboot,
 * but only if setup was completed (device was paired before).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!AppPreferences.isSetupDone(context)) {
            Log.d("BootReceiver", "Setup not done, skipping auto-start")
            return
        }

        if (!AppPreferences.isAutoConnect(context)) {
            Log.d("BootReceiver", "Auto-connect disabled, skipping")
            return
        }

        Log.d("BootReceiver", "Boot complete — starting OBD2Service")
        val serviceIntent = Intent(context, OBD2Service::class.java).apply {
            action = OBD2Service.ACTION_AUTO_START
        }
        context.startForegroundService(serviceIntent)
    }
}
