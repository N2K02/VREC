package com.vrec.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Integration contracts for precomputing saved call waveforms off the player path. */
class RecordingWaveformBackgroundContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun finalizedImportEnqueuesWorkOnlyAfterMetadataSaved() {
        val source = read("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val meta = source.indexOf("check(RecordingStore.writeMeta(")
        val notify = source.indexOf("RecordingStore.notifySaved()", meta)
        val queue = source.indexOf("RecordingWaveformWorker.schedule(context, dest)", notify)
        assertTrue(meta >= 0 && notify > meta && queue > notify)
    }

    @Test fun waveformWorkerIsUniqueBatteryAwareAndOnlyReadsFinalizedFiles() {
        val source = read("app/src/main/java/com/vrec/app/recording/RecordingWaveformWorker.kt")
        assertTrue(source.contains("ExistingWorkPolicy.KEEP"))
        assertTrue(source.contains("setRequiresBatteryNotLow(true)"))
        assertTrue(source.contains("filename != File(filename).name"))
        assertTrue(source.contains("RecordingStore.hasValidMetadata(audio)"))
        assertTrue(source.contains("RecordingWaveform.prepare(applicationContext, audio)"))
    }

    @Test fun persistentCacheIsAtomicVersionedAndInvalidatedOnAudioModification() {
        val source = read("app/src/main/java/com/vrec/app/recording/RecordingWaveform.kt")
        assertTrue(source.contains("AtomicFile(target)"))
        assertTrue(source.contains("file.length() + \":\" + file.lastModified()"))
        assertTrue(source.contains("CACHE_VERSION"))
        assertTrue(source.contains("fun cached(context: Context, file: File)"))
        assertTrue(source.contains("if (!file.isFile || identity(file) != before) return null"))
    }

    @Test fun playerReusesDiskCacheAndOldFilesBackfillInBackground() {
        val source = read("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        assertTrue(source.contains("RecordingWaveformWorker.scheduleMissing("))
        assertTrue(source.contains("RecordingWaveform.prepare(context.applicationContext, rec.file)"))
    }
}
