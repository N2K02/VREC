package com.vrec.app.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Daily, on-device storage housekeeping. Never deletes saved favorites directly. */
class RecordingCleanupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            RecordingStore.cleanOldSaved(applicationContext)
            RecordingStore.purgeTrash(applicationContext)
            Result.success()
        } catch (e: Exception) {
            com.vrec.app.Log.w("Recording retention cleanup failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "vrec-recording-retention"
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecordingCleanupWorker>(1, TimeUnit.DAYS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
