package com.vrec.app.recording

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vrec.app.Log
import com.vrec.app.calls.CallMonitor
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One authenticated transport for notification, Home, mini-bar and floating
 * overlay. A synchronous CAS reserves each command before it is dispatched,
 * so rapid taps on different controls cannot issue overlapping shell commands.
 */
class EnhancedCaptureControlReceiver : BroadcastReceiver() {
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pendingAction = MutableStateFlow<Char?>(null)

        /** Used by UI and floating overlay; notification PendingIntents use onReceive. */
        fun request(context: Context, action: Char) {
            if (!CaptureControlPolicy.isAllowed(action, AdbFallbackNotice.enhancedActive.value,
                    AdbFallbackNotice.enhancedPaused.value) ||
                !pendingAction.compareAndSet(null, action)) return
            scope.launch {
                try {
                    execute(context.applicationContext, action)
                } finally {
                    pendingAction.value = null
                }
            }
        }

        private suspend fun execute(context: Context, action: Char) {
            try {
                withTimeout(8_000L) {
                    // The previous request can outlive a call. Recheck after dispatch.
                    if (!CaptureControlPolicy.isAllowed(action,
                            AdbFallbackNotice.enhancedActive.value,
                            AdbFallbackNotice.enhancedPaused.value)) return@withTimeout
                    when (val result = ExperimentalCaptureDaemon.captureControl(context, action)) {
                        "PAUSED" -> if (action == 'P')
                            AdbFallbackNotice.setEnhancedPaused(context, true)
                        "RECORDING" -> if (action == 'R')
                            AdbFallbackNotice.setEnhancedPaused(context, false)
                        "STOPPED" -> if (action == 'S')
                            CallMonitor.onShellStopConfirmed(context)
                        else -> Log.w("Shell capture command not confirmed ($action): $result")
                    }
                }
            } catch (failure: Exception) {
                Log.w("Shell recording control failed: $action", failure)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.vrec.app.SHELL_CONTROL") return
        val action = intent.getStringExtra("control")?.singleOrNull() ?: return
        if (!CaptureControlPolicy.isAllowed(action, AdbFallbackNotice.enhancedActive.value,
                AdbFallbackNotice.enhancedPaused.value) ||
            !pendingAction.compareAndSet(null, action)) return
        val pending = goAsync()
        scope.launch {
            try {
                execute(context.applicationContext, action)
            } finally {
                pendingAction.value = null
                pending.finish()
            }
        }
    }
}
