package com.vrec.app.experimental.adb

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vrec.app.Log
import com.vrec.app.Prefs
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * OS-managed backstop for ADB recovery when Android has killed VRec's process.
 *
 * In-process checks are faster; WorkManager is a best-effort ~15-minute safety
 * net, not a guarantee of continuous execution or immediate boot startup.
 * Every run honors deliberate Quit, disabled recording, existing pairing and
 * authorization/retry policies; it never grants itself ADB approval.
 */
class AdbRecoveryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (AdbUserChoices.manuallyStopped(app) || !AdbUserChoices.connectionWatchdog(app) ||
            !Prefs.recordingEnabled(app))
            return Result.success()
        try {
            AdbLifecycle.recoverOnce(app, "persistent watchdog")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("Persistent ADB watchdog failed; next scheduled check will retry", error)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "vrec-adb-recovery-watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AdbRecoveryWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
