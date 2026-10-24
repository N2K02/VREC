package com.vrec.app

import android.app.Application
import com.vrec.app.update.AppUpdater
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
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
                if (ExperimentalCaptureDaemon.probe(applicationContext)) {
                    ExperimentalCaptureDaemon.setMode(
                        applicationContext,
                        Prefs.recordingEnabled(applicationContext) &&
                            ExperimentalRecording.method(applicationContext) !=
                                ExperimentalRecording.Method.STANDARD,
                    )
                    ExperimentalCaptureDaemon.collect(applicationContext)
                }
            }
        }
    }
}
