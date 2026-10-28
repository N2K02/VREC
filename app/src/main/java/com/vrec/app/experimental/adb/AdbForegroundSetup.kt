package com.vrec.app.experimental.adb

import android.content.Context

/**
 * Single entry point for the Home Start/Retry button and the permission-card
 * helper repair action. Wireless retries run in LocalWirelessAdb's own
 * serialized scope; USB/TCP and healthy-helper reattachment share the central
 * transport coordinator. Navigation itself must never call this method.
 */
object AdbForegroundSetup {
    enum class Outcome { HELPER_READY, WIRELESS_CONNECTING, DISCOVERING, USER_ACTION_REQUIRED }

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
