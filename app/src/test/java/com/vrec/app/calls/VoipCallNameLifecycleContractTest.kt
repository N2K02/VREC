package com.vrec.app.calls

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** End-to-end source contract: title events must survive active call -> journal -> saved audio metadata -> UI. */
class VoipCallNameLifecycleContractTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun newNotificationTitleIsPublishedForTheSameActiveCall() {
        val listener = source("app/src/main/java/com/vrec/app/calls/VoipNotifListener.kt")
        assertTrue(listener.contains("map[sbn.key] = Signal("))
        assertTrue(listener.contains("if (signals[pkg] != latest)"))
        assertTrue(listener.contains("publish(pkg, false)"))
        assertTrue(listener.contains("CallMonitor.onNotification(this, pkg, phone, status, metadata, flow, media)"))
        // Multiple independent notifications cannot be merged into one made-up participant title.
        assertTrue(listener.contains(".distinct().singleOrNull()"))
    }

    @Test fun accessibilityContinuesScanningWhenCallerAlreadyKnownAndRequiresVerifiedCallUi() {
        val accessibility = source("app/src/main/java/com/vrec/app/calls/VrecAccessibilityService.kt")
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(monitor.contains("candidates.single().isVoip || candidates.single().name == null"))
        assertTrue(accessibility.contains("if (!hasEndCallControl) return"))
        assertTrue(accessibility.contains("CallMonitor.onAccessibilityMetadata(this, pkg, metadata, isDialer)"))
        assertTrue(monitor.contains("if (candidates.size != 1) return // Never attach a caller"))
        assertTrue(monitor.contains("session.callNameHistory = VoipIdentityPolicy.updatedNameHistory("))
        assertTrue(monitor.contains("session.callNameHistory = legacy.callNameHistory"))
    }

    @Test fun updatedGroupCallTitleSurvivesShellImportAndProcessRestart() {
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        val journal = source("app/src/main/java/com/vrec/app/calls/ShellCallMetadataJournal.kt")
        val importer = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val store = source("app/src/main/java/com/vrec/app/recording/Recordings.kt")
        val ui = source("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        assertTrue(monitor.contains("callNameHistory = session.callNameHistory"))
        assertTrue(journal.contains(".put(\"callNameHistory\", JSONArray("))
        assertTrue(journal.contains("optJSONArray(\"callNameHistory\")"))
        assertTrue(importer.contains("callNameHistory = caller?.callNameHistory.orEmpty()"))
        assertTrue(store.contains(".put(\"callNameHistory\", JSONArray(callNameHistory))"))
        assertTrue(store.contains("meta?.optJSONArray(\"callNameHistory\")"))
        assertTrue(store.contains("val latestCallName: String? get() = callNameHistory.lastOrNull() ?: originalCallerName"))
        assertTrue(ui.contains("DetailLine(\"Call name (latest)\", rec.latestCallName)"))
        assertTrue(ui.contains("DetailLine(\"Call name changes\", rec.callNameHistory.joinToString("))
        assertFalse(journal.contains("put(\"nameHistory\", JSONObject.NULL)"))
    }

    @Test fun metadataUpdatesNeverRenameAudioOrConfuseVoipAndCellular() {
        val importer = source("app/src/main/java/com/vrec/app/experimental/daemon/ExperimentalCaptureDaemon.kt")
        val monitor = source("app/src/main/java/com/vrec/app/calls/CallMonitor.kt")
        assertTrue(importer.contains("val dest = File(RecordingStore.dir(context), name.removeSuffix(\".dat\") + \".ogg\")"))
        assertTrue(monitor.contains("session.id + \":\" + started"))
        assertTrue(monitor.contains("session.packageName, session.name, session.number"))
        assertTrue(monitor.contains("if (candidates.size != 1) return"))
        assertFalse(monitor.contains("logCallNameHistory("))
    }
}
