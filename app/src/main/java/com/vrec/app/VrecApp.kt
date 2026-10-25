package com.vrec.app

import android.app.Application
import com.vrec.app.update.AppUpdater
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VrecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppUpdater.init(this, repo = "N2K02/VREC", appName = "VREC")
        // Reattach an existing helper at process start. If it was killed,
        // retry only a connection the user previously authorized.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { AdbHelperAutoRecovery.check(applicationContext) }
                .onFailure { Log.w("ADB helper recovery failed", it) }
        }
    }
}
