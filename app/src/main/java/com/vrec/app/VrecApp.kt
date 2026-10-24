package com.vrec.app

import android.app.Application
import com.vrec.app.update.AppUpdater
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VrecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppUpdater.init(this, repo = "N2K02/VREC", appName = "VREC")
        // Recheck the local shell daemon after process restart without ADB.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                // Reconnect to an already-running shell Java service over the
                // authenticated loopback channel; no active ADB session needed.
                // If it is gone, do not silently reopen debugging or re-arm calls.
                if (AdbPrivilegedApiHelper.reconnect(applicationContext)) {
                    ExperimentalCaptureDaemon.collect(applicationContext)
                }
            }
        }
    }
}
