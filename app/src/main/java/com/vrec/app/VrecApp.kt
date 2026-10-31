package com.vrec.app

import android.app.Application
import com.vrec.app.update.AppUpdater

class VrecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize diagnostics before updater, ADB discovery and recovery can emit events.
        Log.initialize(this)
        AppUpdater.init(this, repo = "N2K02/VREC", appName = "VREC")
        // Do not start recovery from Application.onCreate: Android creates the
        // process BEFORE delivering BOOT_COMPLETED, so that would ignore the
        // Start on boot switch. Activity, boot receiver, and enabled watchdog
        // jobs own their respective entry points. Deliberate Quit remains
        // persistent until the user opens the Activity.
        // AdbUserChoices.manuallyStopped(this) is checked at those entry points.
    }
}
