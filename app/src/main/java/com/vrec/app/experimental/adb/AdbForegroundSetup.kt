package com.vrec.app.experimental.adb

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single entry point for the Home Start/Retry button and the permission-card
 * helper repair action. Shizuku setup shares the transport coordinator with
 * background recovery. Navigation itself must never call this method.
 */
object AdbForegroundSetup {
    enum class Outcome { HELPER_READY, USER_ACTION_REQUIRED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val starting = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    val busy = MutableStateFlow(false)
    val lastOutcome = MutableStateFlow<Outcome?>(null)

    /**
     * Explicit Start must continue if Home is removed from the composition.
     * An app-owned job prevents navigation from cancelling shell bootstrap.
     * Rapid taps are ignored rather than queueing multiple authorization runs.
     */
    fun begin(context: Context): Boolean {
        if (AdbLifecycle.quitting || !starting.compareAndSet(false, true)) return false
        busy.value = true
        lastOutcome.value = null
        val requestGeneration = generation.get()
        val app = context.applicationContext
        scope.launch {
            try {
                lastOutcome.value = startOrRepair(app)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                LocalAdbMiddleware.lastSetupFailed.value = true
                LocalAdbMiddleware.message.value = "ADB setup failed: " +
                    (failure.message ?: "unexpected error") + ". Check Diagnostics and retry."
                lastOutcome.value = Outcome.USER_ACTION_REQUIRED
            } finally {
                // A former job finishing after Quit must not clear the new
                // request's busy state when the Activity is opened again.
                if (generation.get() == requestGeneration) {
                    busy.value = false
                    starting.set(false)
                }
            }
        }
        return true
    }

    /** Quit stops any explicit setup, without destroying the reusable app scope. */
    fun shutdown() {
        generation.incrementAndGet()
        scope.coroutineContext.cancelChildren()
        busy.value = false
        starting.set(false)
        lastOutcome.value = null
    }

    suspend fun startOrRepair(context: Context): Outcome {
        val app = context.applicationContext
        if (AdbLifecycle.quitting) return Outcome.USER_ACTION_REQUIRED
        val result = AdbLifecycle.coordinateSetup {
            ExperimentalRecording.connectShizuku(app, automatic = false)
        }
        LocalAdbMiddleware.lastSetupFailed.value = result.isFailure
        LocalAdbMiddleware.message.value = ExperimentalRecording.status.value.message
        return if (result.isSuccess) Outcome.HELPER_READY else Outcome.USER_ACTION_REQUIRED
    }
}
