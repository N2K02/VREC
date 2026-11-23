package com.vrec.app.experimental.adb

import android.content.Context
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vrec.app.Log
import com.vrec.app.Prefs
import kotlinx.coroutines.CancellationException
import java.time.Duration

/**
 * Wakes VRec the moment USB debugging (`adb_enabled`) changes.
 *
 * Adapted from JemRec's UsbDebuggingWatch (Apache-2.0, see shellserver/NOTICE).
 * Some phones' Settings app writes adb_enabled=0 by itself when Developer
 * options is shown; adbd then stops at once and init kills the recording
 * helper (it lives in adbd's cgroup and shell cannot leave it). The helper's
 * own shield dies in that same instant, so nothing would notice until the
 * next 15-minute watchdog run. A content-URI trigger is watched by the system
 * with nothing of VRec running, and starts this worker within about a second.
 * A trigger fires once, so every run arms the next one first.
 */
class UsbDebuggingWatch(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        // Re-arm before anything else. APPEND_OR_REPLACE: this run still holds
        // the unique name, so KEEP would arm nothing and REPLACE would cancel us.
        arm(app, ExistingWorkPolicy.APPEND_OR_REPLACE)
        val signals = Signals(
            manuallyStopped = AdbUserChoices.manuallyStopped(app),
            quitting = AdbLifecycle.quitting,
            recordingEnabled = Prefs.recordingEnabled(app),
            mayWriteSecureSettings = AdbSelfRecovery.hasSecureSettings(app),
        )
        if (!shouldRevive(signals)) return Result.success()
        Log.i("USB debugging changed; checking the recording helper")
        try {
            // Put the off-Wi-Fi shield back first: with no ADB transport
            // enabled adbd stays stopped and nothing can start the helper.
            if (shouldRestoreShield(signals) && readUsbDebugging(app) != true) {
                Settings.Global.putInt(app.contentResolver, ADB_ENABLED, 1)
                Log.i("USB debugging switched back on as the off-Wi-Fi shield")
            }
            AdbLifecycle.recoverOnce(app, "USB debugging changed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("USB debugging watch could not revive the helper", error)
        }
        return Result.success()
    }

    data class Signals(
        val manuallyStopped: Boolean,
        val quitting: Boolean,
        val recordingEnabled: Boolean,
        val mayWriteSecureSettings: Boolean,
    )

    companion object {
        private const val UNIQUE_NAME = "vrec-usb-debugging-watch"
        const val ADB_ENABLED = "adb_enabled"

        /** JobScheduler's floors; the defaults (10 s / 2 min) let a call ring through. */
        internal val UPDATE_DELAY: Duration = Duration.ofMillis(500)
        internal val MAX_DELAY: Duration = Duration.ofSeconds(1)

        /** A deliberate Quit and recording OFF both mean: leave everything alone. */
        fun shouldRevive(s: Signals): Boolean =
            !s.manuallyStopped && !s.quitting && s.recordingEnabled

        fun shouldRestoreShield(s: Signals): Boolean =
            shouldRevive(s) && s.mayWriteSecureSettings

        /** Null when Android hides the value (Android 17 restricts reads of adb_enabled). */
        private fun readUsbDebugging(context: Context): Boolean? = try {
            Settings.Global.getString(context.contentResolver, ADB_ENABLED)?.trim()?.let { it == "1" }
        } catch (_: SecurityException) { null }

        /**
         * A network is requested (any, not validated): Android blocks loopback
         * sockets for jobs of apps in restricted standby buckets unless the job
         * asks for one, and every recovery path talks to adbd over 127.0.0.1.
         */
        internal fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .addContentUriTrigger(Settings.Global.getUriFor(ADB_ENABLED), false)
            .setTriggerContentUpdateDelay(UPDATE_DELAY)
            .setTriggerContentMaxDelay(MAX_DELAY)
            .build()

        /** Idempotent with KEEP: safe on every app open and background start. */
        fun arm(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<UsbDebuggingWatch>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_NAME, policy, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
