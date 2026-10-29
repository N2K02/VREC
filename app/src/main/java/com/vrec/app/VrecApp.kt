package com.vrec.app

import android.app.Application
import com.vrec.app.experimental.adb.AdbSelfRecovery
import com.vrec.app.experimental.adb.AdbLifecycle
import com.vrec.app.update.AppUpdater
import com.vrec.app.experimental.adb.LocalWirelessAdb

class VrecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize diagnostics before updater, ADB discovery and recovery can emit events.
        Log.initialize(this)
        AppUpdater.init(this, repo = "N2K02/VREC", appName = "VREC")
        // Re-discover a previously paired wireless endpoint after a process restart.
        if (com.vrec.app.experimental.adb.ExperimentalRecording.wirelessRecoveryAllowed(this) &&
            AdbSelfRecovery.onWifi(this))
            LocalWirelessAdb.start(this)
        // Reattach or restore the helper at process start (also after boot, when the
        // accessibility service starts the process), then periodically and on Wi-Fi.
        // Owned by AdbLifecycle so Quit can stop all of it.
        AdbLifecycle.startBackground(this)
    }
}
