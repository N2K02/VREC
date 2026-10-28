package com.vrec.app.recording

import org.junit.Assert.*
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import org.junit.Test

class ShellRecordingLifecycleTest {
    private val first = "vrec_rec_100.dat"
    private val second = "vrec_rec_101.dat"

    @Test fun normalStartEnd() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.isActive())
        assertTrue(lifecycle.ended(first))
        assertFalse(lifecycle.isActive())
    }

    @Test fun lateStartAfterEndIsRejected() {
        val lifecycle = ShellRecordingLifecycle()
        assertFalse(lifecycle.ended(first))
        assertFalse(lifecycle.started(first))
        assertFalse(lifecycle.isActive())
    }

    @Test fun duplicateWakeCannotRestartOrStopOtherRecording() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertFalse(lifecycle.started(first))
        assertFalse(lifecycle.started(second))
        assertFalse(lifecycle.ended(second))
        assertTrue(lifecycle.isActive())
        assertTrue(lifecycle.ended(first))
        assertFalse(lifecycle.ended(first))
    }

    @Test fun unrelatedEndCannotPoisonFutureRecordingIdentity() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertFalse(lifecycle.ended(second))
        assertTrue(lifecycle.ended(first))
        assertTrue(lifecycle.started(second))
        assertTrue(lifecycle.isActive())
    }

    @Test fun invalidFileRejected() {
        val lifecycle = ShellRecordingLifecycle()
        assertFalse(lifecycle.started("../vrec_rec_100.dat"))
        assertFalse(lifecycle.started("vrec_rec_100.mp3"))
        assertFalse(lifecycle.started(""))
    }

    @Test fun renamedFinalFileStillEndsOriginalCapture() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started("vrec_rec_100_in.dat"))
        assertTrue(lifecycle.ended("vrec_rec_100_in_taken.dat"))
        assertFalse(lifecycle.isActive())
        assertFalse(lifecycle.started("vrec_rec_100_in.dat"))
    }

    @Test fun confirmedStopPreventsDelayedStartAndAllowsNextSession() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.stoppedByUser())
        assertFalse(lifecycle.isActive())
        assertFalse(lifecycle.started(first))
        assertFalse(lifecycle.ended(first))
        assertTrue(lifecycle.started(second))
        assertTrue(lifecycle.stoppedByUser())
        assertFalse(lifecycle.started(second))
    }

    @Test fun stoppingWithoutActiveSessionDoesNotPoisonNextCall() {
        val lifecycle = ShellRecordingLifecycle()
        assertFalse(lifecycle.stoppedByUser())
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.ended(first))
        assertFalse(lifecycle.stoppedByUser())
    }

    @Test fun pauseAllowsFreshResumeFileAndRejectsLateOldWake() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.pausedByUser())
        assertFalse(lifecycle.isActive())
        assertFalse(lifecycle.started(first))
        assertTrue(lifecycle.started(second))
        assertFalse(lifecycle.ended(first))
        assertTrue(lifecycle.isActive())
        assertTrue(lifecycle.stoppedByUser())
        assertFalse(lifecycle.started(second))
    }

    @Test fun pausedReplyAfterEndedWakeStillAcknowledgesPausedSession() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.ended(first))
        assertTrue(lifecycle.pausedByUser()) // authenticated PAUSED arrives late
        assertFalse(lifecycle.pausedByUser()) // no duplicate transition
        assertTrue(lifecycle.started(second)) // resume has a new identity
        assertFalse(lifecycle.ended(first))
        assertTrue(lifecycle.isActive())
    }

    @Test fun authenticatedLiveSnapshotRepairsPrematureEndWake() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.ended(first))
        assertFalse(lifecycle.started(first))
        // The shell still reports this file actively recording. Its
        // authenticated status supersedes the reordered END notification.
        assertTrue(lifecycle.reconcileRecording(first))
        assertTrue(lifecycle.isActive())
        assertFalse(lifecycle.started(first))
        assertTrue(lifecycle.ended(first))
        assertFalse(lifecycle.isActive())
    }

    @Test fun authenticatedSnapshotReplacesMissedEndAndStart() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.reconcileRecording(second))
        assertFalse(lifecycle.ended(first))
        assertFalse(lifecycle.started(first))
        assertTrue(lifecycle.isActive())
        assertFalse(lifecycle.reconcileRecording(second))
        assertTrue(lifecycle.ended(second))
    }

    @Test fun authenticatedPausedSnapshotRetiresStaleRecordingIdentity() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        lifecycle.reconcilePaused()
        assertFalse(lifecycle.isActive())
        assertFalse(lifecycle.started(first))
        assertFalse(lifecycle.ended(first))
        assertTrue(lifecycle.started(second))
        assertTrue(lifecycle.isActive())
    }

    @Test fun processRestartSnapshotRehydratesAndResumesWithoutStaleWakes() {
        val lifecycle = ShellRecordingLifecycle() // fresh Android process
        val snapshot = ExperimentalCaptureDaemon.parseCaptureSnapshot("RECORDING vrec_rec_100.dat")
        val file = (snapshot as ExperimentalCaptureDaemon.CaptureSnapshot.Recording).file
        assertTrue(lifecycle.started(file))
        assertFalse(lifecycle.started(file)) // duplicate wake from helper
        assertTrue(lifecycle.pausedByUser())
        assertFalse(lifecycle.started(file)) // delayed old wake
        assertTrue(lifecycle.started(second)) // new resumed shell file
        assertFalse(lifecycle.ended(file)) // delayed previous END
        assertTrue(lifecycle.isActive())
        assertTrue(lifecycle.ended(second))
        assertFalse(lifecycle.isActive())
    }

    @Test fun subsequentCallWorks() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.ended(first))
        assertTrue(lifecycle.started(second))
    }

    @Test fun rapidPauseStopAndResumeCannotResurrectStoppedCapture() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.pausedByUser())
        assertFalse(lifecycle.pausedByUser())
        assertTrue(lifecycle.started(second))
        assertTrue(lifecycle.stoppedByUser())
        assertFalse(lifecycle.isActive())
        assertFalse(lifecycle.started(first))
        assertFalse(lifecycle.started(second))
        assertFalse(lifecycle.ended(second))
    }

    @Test fun restoredPausedStateRejectsOldWakesUntilNewSegmentStarts() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        lifecycle.reconcilePaused()
        assertFalse(lifecycle.started(first))
        assertFalse(lifecycle.ended(first))
        assertTrue(lifecycle.started(second))
        assertTrue(lifecycle.isActive())
    }
}
