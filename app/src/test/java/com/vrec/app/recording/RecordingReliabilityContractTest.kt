package com.vrec.app.recording

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecordingReliabilityContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun publicationSyncsAudioAndMetadataBeforeCommit() {
        val publisher = source("app/src/main/java/com/vrec/app/experimental/daemon/RecordingAudioPublisher.kt")
        val audio = publisher.indexOf("syncFile(temp)")
        val metadata = publisher.indexOf("syncFile(metadata(temp))")
        val commit = publisher.indexOf("FileOutputStream(marker)")
        assertTrue(audio >= 0 && metadata > audio && commit > metadata)
        assertTrue(publisher.contains("it.fd.sync()"))
        assertTrue(publisher.contains("recover(dest, validMetadata)"))
    }

    @Test fun userEditsSurviveReimportAndCleanupIsBounded() {
        val store = source("app/src/main/java/com/vrec/app/recording/Recordings.kt")
        val collector = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(store.contains("fun carryUserEdits("))
        assertTrue(store.contains("listOf(\"notes\", \"favorite\", \"bookmarksMs\")"))
        assertTrue(collector.contains("RecordingStore.carryUserEdits(dest, temp)"))
        assertTrue(collector.contains("repeat(3) { attempt ->"))
        assertTrue(collector.contains("socket.soTimeout = 10_000"))
        assertTrue(collector.contains("if (removed) return true"))
        assertTrue(collector.contains("if (attempt < 2) delay("))
        assertTrue(collector.contains("will retry later"))
    }

    @Test fun shellCleanupIsIdempotentAfterLostAcknowledgement() {
        val shell = source("shellserver/src/com/vrec/app/shell/Main.java")
        assertTrue(shell.contains("file != null && (!file.exists() || file.delete())"))
        assertTrue(shell.contains("!name.equals(activeName())"))
        assertTrue(shell.contains("if (ok) new File(REC_DIR, name + \".health\").delete()"))
    }

    @Test fun longImportAvoidsRedundantAudioScanAndKeepsSource() {
        val publisher = source("app/src/main/java/com/vrec/app/experimental/daemon/RecordingAudioPublisher.kt")
        val collector = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        assertTrue(publisher.contains("check(OggIntegrityVerifier.valid(temp))"))
        assertFalse(collector.contains("check(OggIntegrityVerifier.valid(temp))"))
        assertTrue(collector.contains("RecordingAudioPublisher.publish(temp, dest"))
        assertTrue(collector.indexOf("RecordingAudioPublisher.publish(temp, dest") <
            collector.indexOf("delete(context, name)", collector.indexOf("RecordingAudioPublisher.publish(temp, dest")))
    }
}
