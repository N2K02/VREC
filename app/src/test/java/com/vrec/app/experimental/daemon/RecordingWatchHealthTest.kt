package com.vrec.app.experimental.daemon

import org.junit.Assert.*
import org.junit.Test

class RecordingWatchHealthTest {
    private val valid = "WATCH 1 since=100 watching=1 looper=ok mode=1 call=0 events=3 lastevent=90 offhooks=2 lastoffhook=90 recording=0 voip=0"
    @Test fun healthyWatcherPasses() {
        val snapshot = RecordingWatchHealth.parse(valid)
        assertNotNull(snapshot)
        assertTrue(RecordingWatchHealth.healthy(snapshot, 1))
    }
    @Test fun deadWatcherFailsEvenWhenDaemonAnswers() {
        assertFalse(RecordingWatchHealth.healthy(RecordingWatchHealth.parse(valid.replace("watching=1", "watching=0")), 1))
    }
    @Test fun stuckLooperAndWrongModeFail() {
        assertFalse(RecordingWatchHealth.healthy(RecordingWatchHealth.parse(valid.replace("looper=ok", "looper=stuck")), 1))
        assertFalse(RecordingWatchHealth.healthy(RecordingWatchHealth.parse(valid), 2))
    }
    @Test fun malformedOrOldProtocolFailsClosed() {
        assertNull(RecordingWatchHealth.parse("WATCH 0 watching=1"))
        assertNull(RecordingWatchHealth.parse(valid.replace("events=3", "events=not-a-number")))
    }
    @Test fun unexpectedLingeringRecordingFails() {
        assertFalse(RecordingWatchHealth.healthy(RecordingWatchHealth.parse(valid.replace("recording=0", "recording=95")), 1))
    }
    @Test fun activeCallWithRecordingIsNotFlagged() {
        assertTrue(RecordingWatchHealth.healthy(RecordingWatchHealth.parse(valid.replace("call=0", "call=1").replace("recording=0", "recording=95")), 1))
    }
}
