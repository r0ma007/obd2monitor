package com.obd2monitor.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.obd2monitor.service.OBD2Service

/**
 * Transparent, no-UI activity.
 * Purpose: give Automate (or any launcher) a target to start the app.
 * Immediately starts OBD2Service in foreground and finishes — no screen shown.
 *
 * Automate block: "App Start" → package: com.obd2monitor
 *                               activity: .ui.BackgroundLaunchActivity
 */
class BackgroundLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the foreground service
        val serviceIntent = Intent(this, OBD2Service::class.java).apply {
            action = OBD2Service.ACTION_AUTO_START
        }
        startForegroundService(serviceIntent)

        // Done — no UI needed
        finish()
    }
}
