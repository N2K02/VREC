package com.vrec.app.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import com.vrec.app.Log
import com.vrec.app.calls.CallMonitor

/** Notification controls use the same authenticated daemon protocol as the overlay. */
class EnhancedCaptureControlReceiver : BroadcastReceiver() {
    companion object {
        // BroadcastReceiver instances are not stable across deliveries. Serialize
        // notification actions globally so Pause/Resume/Stop cannot overtake.
        private val controlMutex = Mutex()
        /** Shared between Home, mini-bar, notification and overlay. */
        val pendingAction = MutableStateFlow<Char?>(null)

        fun request(context: Context, action: Char) {
            if (action !in "PRS" || !AdbFallbackNotice.enhancedActive.value ||
                pendingAction.value != null) return
            context.sendBroadcast(
                Intent(context, EnhancedCaptureControlReceiver::class.java)
                    .setAction("com.vrec.app.SHELL_CONTROL")
                    .putExtra("control", action.toString())
            )
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.vrec.app.SHELL_CONTROL" ||
            !AdbFallbackNotice.enhancedActive.value) return
        val action = intent.getStringExtra("control")?.singleOrNull() ?: return
        if (action !in "PRS" || pendingAction.value != null) return
        pendingAction.value = action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // goAsync receivers have a limited execution window. Never
                // leave a broadcast pending indefinitely on a stalled socket.
                withTimeout(8_000L) {
                    controlMutex.withLock {
                        // The recording may have ended while this broadcast
                        // waited for an earlier command to finish.
                        if (!AdbFallbackNotice.enhancedActive.value) return@withLock
                        val result = ExperimentalCaptureDaemon.captureControl(
                            context.applicationContext, action)
                        when (result) {
                            "PAUSED" -> {
                                if (action == 'P') AdbFallbackNotice.setEnhancedPaused(context, true)
                            }
                            "RECORDING" -> {
                                if (action == 'R') AdbFallbackNotice.setEnhancedPaused(context, false)
                            }
                            "STOPPED" -> {
                                if (action == 'S') CallMonitor.onShellStopConfirmed(context)
                            }
                            else -> Log.w("Shell recording control not confirmed: $action")
                        }
                    }
                }
            } catch (failure: Exception) {
                Log.w("Shell notification control failed", failure)
            } finally {
                pendingAction.value = null
                pending.finish()
            }
        }
    }
}
