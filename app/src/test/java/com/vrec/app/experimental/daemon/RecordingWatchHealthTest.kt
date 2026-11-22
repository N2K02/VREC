package com.vrec.app.experimental.daemon

import java.net.ConnectException
import java.net.SocketTimeoutException
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
    @Test fun transportFailureAndInvalidReportsHaveDistinctMessages() {
        assertTrue(RecordingWatchHealth.unavailableDetail(ConnectException()).contains("stopped"))
        assertTrue(RecordingWatchHealth.unavailableDetail(SocketTimeoutException()).contains("did not respond"))
        assertTrue(RecordingWatchHealth.unavailableDetail(null).contains("no compatible watch report"))
    }

    @Test fun malformedVoipArmingStatusFailsClosed() {
        assertNull(RecordingWatchHealth.parse("$valid voiparmed=error"))
        assertFalse(requireNotNull(RecordingWatchHealth.parse(valid)).voipArmed)
        assertTrue(requireNotNull(RecordingWatchHealth.parse("$valid voiparmed=1")).voipArmed)
    }

    @Test fun enabledVoipNeedsAnIndependentWatcherAcknowledgement() {
        val simOnly = requireNotNull(RecordingWatchHealth.parse(valid))
        assertNull(RecordingWatchHealth.issue(simOnly, 1, voipExpected = false))
        assertNotNull(RecordingWatchHealth.issue(simOnly, 1, voipExpected = true))
        val armed = requireNotNull(RecordingWatchHealth.parse("$valid voiparmed=1"))
        assertNull(RecordingWatchHealth.issue(armed, 1, voipExpected = true))
        assertNotNull(RecordingWatchHealth.issue(armed.copy(watching = false), 1, voipExpected = true))
    }

    @Test fun authenticationFailureIsDistinctFromAStoppedHelper() {
        val issue = RecordingWatchHealth.unavailableDetail(HelperAuthenticationException())
        assertTrue(issue.contains("could not authenticate"))
        assertFalse(issue.contains("stopped"))
    }
}
