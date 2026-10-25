package com.vrec.app.calls

import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.Recording
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellularCallLogMatchPolicyTest {
    private fun recording(
        direction: CallDirection = CallDirection.INCOMING,
        start: Long = 1_000_000L,
        durationMs: Long = 480_000L,
    ) = Recording(
        file = File("sample.m4a"),
        startedAt = start,
        direction = direction,
        peer = null,
        sourceLabel = null,
        silent = false,
        durationMs = durationMs,
        endedAt = start + durationMs,
    )

    private fun entry(
        number: String = "+23057045289",
        start: Long = 992_000L,
        duration: Long = 480L,
        type: Int = 1,
    ) = CellularCallLogEntry(number, "Sonu orange", start, duration, type)

    @Test fun recoversCompletedIncomingCallEvenWhenLogStartsDuringRinging() {
        val actual = entry()
        val wrongDirection = entry(type = 2, number = "+23012345678")
        assertEquals(actual,
            CellularCallLogMatchPolicy.bestMatch(recording(), listOf(wrongDirection, actual)))
    }

    @Test fun recoversOutgoingWithoutGuessingFromVoipOrManualRecording() {
        val call = entry(type = 2)
        assertEquals(call, CellularCallLogMatchPolicy.bestMatch(
            recording(direction = CallDirection.OUTGOING), listOf(call)))
        assertNull(CellularCallLogMatchPolicy.bestMatch(
            recording(direction = CallDirection.VOIP), listOf(call)))
        assertNull(CellularCallLogMatchPolicy.bestMatch(
            recording(direction = CallDirection.UNKNOWN), listOf(call)))
    }

    @Test fun rejectsNeighbouringAndPrivateCalls() {
        assertNull(CellularCallLogMatchPolicy.bestMatch(recording(), listOf(entry(start = 100_000L))))
        assertNull(CellularCallLogMatchPolicy.bestMatch(recording(), listOf(entry(number = "Unknown"))))
        assertNull(CellularCallLogMatchPolicy.bestMatch(recording(), listOf(entry(duration = 5L))))
    }

    @Test fun rejectsAmbiguousCallsRatherThanMislabelRecording() {
        val first = entry(number = "+23057045289")
        val second = entry(number = "+23059876543", start = 987_000L)
        assertNull(CellularCallLogMatchPolicy.bestMatch(recording(), listOf(first, second)))
    }

    @Test fun prefersClearlyCloserCallWhenMultipleEntriesExist() {
        val correct = entry()
        val unrelated = entry(number = "+23059876543", start = 830_000L, duration = 480L)
        assertEquals(correct,
            CellularCallLogMatchPolicy.bestMatch(recording(), listOf(correct, unrelated)))
    }
}
