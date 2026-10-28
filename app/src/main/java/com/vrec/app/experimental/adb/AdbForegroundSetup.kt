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
 * helper repair action. Wireless retries run in LocalWirelessAdb's own
 * serialized scope; USB/TCP and healthy-helper reattachment share the central
 * transport coordinator. Navigation itself must never call this method.
 */
object AdbForegroundSetup {
    enum class Outcome { HELPER_READY, WIRELESS_CONNECTING, DISCOVERING, USER_ACTION_REQUIRED }

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

    internal object Routing {
        enum class Next { RETRY_PAIRED_WIRELESS, RESTORE_HELPER_OR_TCP }
        fun next(connectPort: Int?, paired: Boolean): Next =
            if (connectPort != null && paired) Next.RETRY_PAIRED_WIRELESS
            else Next.RESTORE_HELPER_OR_TCP
    }

    suspend fun startOrRepair(context: Context): Outcome {
        val app = context.applicationContext
        if (AdbLifecycle.quitting) return Outcome.USER_ACTION_REQUIRED
        val wireless = LocalWirelessAdb.status.value
        val paired = wireless.paired || ExperimentalRecording.wirelessWasPaired(app)
        if (Routing.next(wireless.connectPort, paired) == Routing.Next.RETRY_PAIRED_WIRELESS) {
            // retry() queues the whole connect -> UID 2000 -> TCP -> helper
            // transaction. Do not hold coordinateSetup around its coroutine:
            // the actual retry takes that same mutex when it runs.
            LocalWirelessAdb.retry(app)
            return Outcome.WIRELESS_CONNECTING
        }
        val restored = AdbLifecycle.coordinateSetup {
            LocalAdbMiddleware.setup(app, userInitiated = true)
        }
        if (restored) return Outcome.HELPER_READY
        if (!ExperimentalRecording.tcpPortListening()) {
            LocalWirelessAdb.start(app, userInitiated = true)
            return Outcome.DISCOVERING
        }
        return Outcome.USER_ACTION_REQUIRED
    }
}
