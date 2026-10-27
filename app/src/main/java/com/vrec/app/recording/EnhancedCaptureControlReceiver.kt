package com.vrec.app.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Notification controls use the same authenticated daemon protocol as the overlay. */
class EnhancedCaptureControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.vrec.app.SHELL_CONTROL" ||
            !AdbFallbackNotice.enhancedActive.value) return
        val action = intent.getStringExtra("control")?.singleOrNull() ?: return
        if (action !in "PRS") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = ExperimentalCaptureDaemon.captureControl(context.applicationContext, action)
                when (result) {
                    "PAUSED" -> AdbFallbackNotice.setEnhancedPaused(context, true)
                    "RECORDING" -> AdbFallbackNotice.setEnhancedPaused(context, false)
                    "STOPPED" -> AdbFallbackNotice.shellStoppedByUser(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
