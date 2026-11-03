package com.vrec.app.calls

import org.junit.Assert.*
import org.junit.Test

class VoipCallLogPolicyTest {
    private val start = 1_000_000L
    private fun row(number: String? = "51234567", pkg: String? = "com.whatsapp", date: Long = start,
                    duration: Long = 60L, type: Int = 2, account: String? = "wa") =
        VoipCallLogEntry(pkg, account, number, date, duration, type)
    private fun find(vararg rows: VoipCallLogEntry, flow: String? = "OUTGOING") =
        VoipCallLogPolicy.numbers("com.whatsapp", start, start + 60_000L, 60_000L, flow, rows.toList())

    @Test fun collectsEveryExplicitNumberFromOneAppSession() {
        assertEquals(listOf("51234567", "59876543"), find(row(), row("59876543"), row("5123 4567")))
    }

    @Test fun excludesOtherAppsSimCallsAndMissingSource() {
        assertEquals(listOf("51234567"), find(row(), row("59876543", "com.android.phone"),
            row("57654321", "org.telegram.messenger"), row("56789012", null)))
    }

    @Test fun neverAssignsNumbersFromNearbyOrAmbiguousCalls() {
        assertTrue(find(row(), row("59876543", date = start + 5_000L)).isEmpty())
        assertTrue(find(row(), row("59876543", account = "other-account")).isEmpty())
        assertTrue(find(row(date = start - 600_000L)).isEmpty())
    }

    @Test fun rejectsWrongDirectionUnansweredCallsAndDurationMismatch() {
        assertTrue(find(row(type = 1)).isEmpty())
        assertTrue(find(row(type = 3)).isEmpty())
        assertTrue(find(row(duration = 0)).isEmpty())
        assertTrue(find(row(duration = 180)).isEmpty())
    }

    @Test fun neverInfersNumbersFromNamesAndHandlesUnavailableHistory() {
        assertTrue(find(row("titi"), row("et"), row(null)).isEmpty())
        assertTrue(find().isEmpty())
        assertEquals(listOf("51234567"), find(row(), flow = null))
    }

    @Test fun missingTimingAndShortRecordingsStayUnknown() {
        assertTrue(VoipCallLogPolicy.numbers("com.whatsapp", 0L, start, 60_000L, null, listOf(row())).isEmpty())
        assertTrue(VoipCallLogPolicy.numbers("com.whatsapp", start, start + 1000, 1000, null, listOf(row())).isEmpty())
    }
}
