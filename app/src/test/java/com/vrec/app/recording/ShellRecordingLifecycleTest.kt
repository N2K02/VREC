package com.vrec.app.recording

import org.junit.Assert.*
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

    @Test fun invalidFileRejected() {
        val lifecycle = ShellRecordingLifecycle()
        assertFalse(lifecycle.started("../vrec_rec_100.dat"))
        assertFalse(lifecycle.started("vrec_rec_100.mp3"))
        assertFalse(lifecycle.started(""))
    }

    @Test fun subsequentCallWorks() {
        val lifecycle = ShellRecordingLifecycle()
        assertTrue(lifecycle.started(first))
        assertTrue(lifecycle.ended(first))
        assertTrue(lifecycle.started(second))
    }
}
