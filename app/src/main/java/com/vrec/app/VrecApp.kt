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
        // WorkManager/boot/call broadcasts can launch this process after Quit.
        // Only opening the activity again may clear the deliberate-stop latch.
        if (!com.vrec.app.experimental.adb.AdbUserChoices.manuallyStopped(this)) {
            if (com.vrec.app.experimental.adb.ExperimentalRecording.wirelessRecoveryAllowed(this) &&
                AdbSelfRecovery.onWifi(this))
                LocalWirelessAdb.start(this)
            AdbLifecycle.startBackground(this)
        }
    }
}
