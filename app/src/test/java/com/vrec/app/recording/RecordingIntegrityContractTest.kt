package com.vrec.app.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contracts complement device tests: they prevent silent loss of
 * recording metadata and regressions to raw, non-atomic JSON writes.
 */
class RecordingIntegrityContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun allSidecarMutationsUseAtomicFile() {
        val store = source("app/src/main/java/com/vrec/app/recording/Recordings.kt")
        assertTrue(store.contains("AtomicFile(sidecar(file))"))
        assertTrue(store.contains("atomic.startWrite()"))
        assertTrue(store.contains("atomic.finishWrite(stream)"))
        assertTrue(store.contains("atomic.failWrite(stream)"))
        assertTrue(store.contains("AtomicFile(sidecar(file)).openRead()"))
        assertFalse(store.contains("sidecar(file).writeText("))
        assertFalse(store.contains("sidecar(recording.file).writeText("))
    }

    @Test fun importsKeepShellOriginalUntilCompleteMetadataIsDurable() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(daemon.contains("RecordingStore.hasValidMetadata(dest)"))
        assertTrue(daemon.contains("check(RecordingStore.writeMeta("))
        assertTrue(daemon.contains("startedAtOverride = started"))
        assertTrue(daemon.contains("audioHealth = health"))
        val metadata = daemon.indexOf("check(RecordingStore.writeMeta(")
        val publish = daemon.indexOf("check(temp.renameTo(dest))")
        val removeShell = daemon.indexOf("delete(context, name)", metadata)
        assertTrue(metadata in 0 until publish)
        assertTrue(publish in 0 until removeShell)
        assertFalse(daemon.contains("sidecar.writeText("))
        assertTrue(daemon.contains("if (t is CancellationException) throw t"))
    }

    @Test fun muxerStopFailurePropagatesBeforeTheImportIsPublished() {
        val writer = source("app/src/main/java/com/vrec/app/experimental/daemon/OpusOggWriter.kt")
            .substringAfter("override fun close()")
            .substringBefore("private companion object")
        assertTrue(writer.contains("ogg.close()"))
        assertFalse(writer.contains("catch"))
        assertFalse(writer.contains("runCatching"))
    }

    @Test fun adbImportUsesSameValidatedOggWriterOnAllAndroidVersions() {
        val writer = source("app/src/main/java/com/vrec/app/experimental/daemon/OpusOggWriter.kt")
        assertTrue(writer.contains("LegacyOpusOggMuxer(FileOutputStream(fd), opusHead)"))
        assertTrue(writer.contains("OpusPacketTiming.samples(data)"))
        assertFalse(writer.contains("MediaMuxer("))
        assertFalse(writer.contains("Build.VERSION.SDK_INT"))
        assertTrue(writer.contains("ogg.close()"))
    }

    @Test fun originalShellRecordingRequiresValidatedOggAndDeleteAcknowledgement() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val validator = source("app/src/main/java/com/vrec/app/experimental/daemon/OggIntegrityVerifier.kt")
        assertTrue(daemon.contains("dest.exists() && OggIntegrityVerifier.valid(dest)"))
        assertTrue(daemon.contains("check(OggIntegrityVerifier.valid(temp))"))
        assertTrue(daemon.contains("check(OggIntegrityVerifier.valid(dest))"))
        assertTrue(daemon.contains("readLine()?.trim() == \"OK\""))
        assertTrue(daemon.contains("Shell recording cleanup was not acknowledged"))
        assertTrue(validator.contains("checksum(packet) != claimed"))
        assertTrue(validator.contains("sawEos && sawAudio"))
    }

    @Test fun actualOpusDurationUsedForSavedMetadataAndCancellationIsNotSwallowed() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val writer = source("app/src/main/java/com/vrec/app/experimental/daemon/OpusOggWriter.kt")
        assertTrue(daemon.contains("durationMs = (muxer.durationSeconds * 1000.0).toLong()"))
        assertFalse(daemon.contains("val durationMs = packets * 20L"))
        assertTrue(writer.contains("OpusPacketTiming.samples(data)"))
        assertTrue(daemon.contains("if (it is CancellationException) throw it"))
    }

    @Test fun failedCaptureAndStorageOperationsHaveVisibleWarnings() {
        val notice = source("app/src/main/java/com/vrec/app/recording/RecordingFailureNotice.kt")
        val home = source("app/src/main/java/com/vrec/app/MainActivity.kt")
        val service = source("app/src/main/java/com/vrec/app/recording/RecordingService.kt")
        val store = source("app/src/main/java/com/vrec/app/recording/Recordings.kt")
        val list = source("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        assertTrue(notice.contains("val warning = MutableStateFlow<String?>(null)"))
        assertTrue(home.contains("RecordingFailureNotice.warning.collectAsState()"))
        assertTrue(service.contains("RecordingFailureNotice.warn(context"))
        assertTrue(store.contains("fun delete(recording: Recording): Boolean"))
        assertTrue(list.contains("Could not save details"))
        assertTrue(list.contains("recording(s) could not be fully deleted"))
    }
    @Test fun whatsappVideoAndContactMetadataSurviveImportAndReload() {
        val daemon = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val journal = source("app/src/main/java/com/vrec/app/calls/ShellCallMetadataJournal.kt")
        val store = source("app/src/main/java/com/vrec/app/recording/Recordings.kt")
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(daemon.contains("journal.findForCellularTaggedVoip(context, started, identity.direction)"))
        assertTrue(daemon.contains("callMediaType = caller?.mediaType"))
        assertTrue(daemon.contains("contactName = contactName"))
        // Address-book fallback is cellular-only; third-party titles must remain intact.
        assertTrue(daemon.contains("caller?.number?.takeUnless { voip }?.let { number ->"))
        assertTrue(journal.contains("selectCellularTaggedVoip("))
        assertTrue(journal.contains("return nearby.singleOrNull()"))
        assertTrue(journal.contains("selectCellularTaggedSim("))
        assertTrue(daemon.contains("journal.consumeMatch(context, caller)"))
        assertTrue(monitor.contains("CallSourceConflictPolicy.suppressFallbackSimOffhook("))
        assertTrue(store.contains(".put(\"callMediaType\""))
        assertTrue(store.contains("callMediaType = meta?.optString(\"callMediaType\")"))
        assertTrue(store.contains(".put(\"contactName\", contactName ?: JSONObject.NULL)"))
        assertTrue(store.contains(".put(\"phoneNumber\", phoneNumber ?: JSONObject.NULL)"))
    }
}
