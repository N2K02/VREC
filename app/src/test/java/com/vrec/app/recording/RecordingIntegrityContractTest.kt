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
        assertTrue(daemon.indexOf("check(RecordingStore.writeMeta(") <
            daemon.indexOf("delete(context, name)", daemon.indexOf("check(RecordingStore.writeMeta(")))
        assertFalse(daemon.contains("sidecar.writeText("))
        assertTrue(daemon.contains("if (t is CancellationException) throw t"))
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
        assertTrue(daemon.contains("caller?.number?.let { number ->"))
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
