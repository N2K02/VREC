package com.vrec.app.recording

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Runs only after the completed audio and its metadata have been imported. */
class RecordingWaveformWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val filename = inputData.getString(KEY_FILE) ?: return@withContext Result.failure()
        // Work input is a basename, not a path. Only app-owned completed recordings
        // are eligible; never decode a shell temp file or arbitrary file path.
        if (filename != File(filename).name ||
            (!filename.endsWith(".ogg", true) && !filename.endsWith(".m4a", true))) {
            return@withContext Result.failure()
        }
        val audio = File(RecordingStore.dir(applicationContext), filename)
        if (!audio.isFile || audio.length() <= 100L ||
            !RecordingStore.hasValidMetadata(audio)) return@withContext Result.success()
        return@withContext try {
            RecordingWaveform.prepare(applicationContext, audio)
            Result.success() // Codec unsupported: playback retains its fallback slider.
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.vrec.app.Log.w("Background waveform cache failed", error)
            Result.retry()
        }
    }

    companion object {
        private const val KEY_FILE = "recording_basename"
        private const val PREFIX = "vrec-waveform-"

        fun schedule(context: Context, file: File) {
            if (!file.isFile || file.length() <= 100L ||
                !RecordingStore.hasValidMetadata(file) ||
                RecordingWaveform.isReady(context, file)) return
            if (file.parentFile?.canonicalFile != RecordingStore.dir(context).canonicalFile)
                return
            val key = file.name + ":" + file.length() + ":" + file.lastModified()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            val request = OneTimeWorkRequestBuilder<RecordingWaveformWorker>()
                .setInputData(Data.Builder().putString(KEY_FILE, file.name).build())
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                PREFIX + digest, ExistingWorkPolicy.KEEP, request
            )
        }

        /** Backfill a few older recordings without decoding on the UI thread. */
        fun scheduleMissing(context: Context, recordings: List<Recording>, max: Int = 5) {
            recordings.asSequence().filter { !RecordingWaveform.isReady(context, it.file) }
                .take(max).forEach { schedule(context, it.file) }
        }
    }
}
