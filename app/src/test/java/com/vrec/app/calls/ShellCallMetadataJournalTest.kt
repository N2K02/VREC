package com.vrec.app.calls

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellCallMetadataJournalTest {
    @Test fun matchesCloseShellAndTelecomStarts() {
        assertTrue(ShellCallMetadataJournal.matches(100_000L, 108_000L, "com.whatsapp", "com.whatsapp"))
    }

    @Test fun rejectsOldOrFutureCallMetadata() {
        assertFalse(ShellCallMetadataJournal.matches(100_000L, 195_000L, "com.whatsapp", "com.whatsapp"))
        assertFalse(ShellCallMetadataJournal.matches(200_000L, 100_000L, "com.whatsapp", "com.whatsapp"))
    }

    @Test fun rejectsDifferentVoipApp() {
        assertFalse(ShellCallMetadataJournal.matches(
            100_000L, 108_000L, "com.whatsapp", "org.telegram.messenger"))
    }

    @Test fun legacyShellFilenameWithoutVerifiedPackageMustNotInheritVoipIdentity() {
        // Time proximity alone could misidentify another caller during call waiting.
        assertFalse(ShellCallMetadataJournal.matches(100_000L, 108_000L, null, "com.whatsapp"))
        assertFalse(ShellCallMetadataJournal.matches(100_000L, 108_000L, "com.whatsapp", null))
    }
    private fun candidate(
        key: String = "telecom-1",
        whenMs: Long = 105_000L,
        kind: CallDirection = CallDirection.VOIP,
        flow: CallDirection = CallDirection.INCOMING,
        pkg: String? = "com.whatsapp",
        name: String? = "Known WhatsApp contact",
        number: String? = null,
        media: String? = "VIDEO",
    ) = ShellCallMetadataJournal.Candidate(key, whenMs, kind, pkg, flow, name, number, media)

    @Test fun mislabeledIncomingShellFileCanRecoverUniqueWhatsappContactAndVideo() {
        val expected = candidate()
        val matched = ShellCallMetadataJournal.selectCellularTaggedVoip(
            100_000L, CallDirection.INCOMING, listOf(expected))
        assertEquals(expected, matched)
        assertEquals("VIDEO", matched?.mediaType)
        assertEquals("com.whatsapp", matched?.packageName)
    }

    @Test fun trueSimCallNearVoipPreventsFalseContactAttribution() {
        val voip = candidate()
        val cellular = candidate(key = "sim-legacy", whenMs = 106_000L,
            kind = CallDirection.INCOMING, pkg = null, name = null)
        assertNull(ShellCallMetadataJournal.selectCellularTaggedVoip(
            100_000L, CallDirection.INCOMING, listOf(voip, cellular)))
        assertNull(ShellCallMetadataJournal.selectCellularTaggedVoip(
            100_000L, CallDirection.INCOMING, listOf(cellular)))
    }

    @Test fun simultaneousDifferentVoipAppsOrCallsNeverGuessCaller() {
        val first = candidate()
        val second = candidate(key = "telecom-2", whenMs = 106_000L,
            pkg = "org.telegram.messenger", name = "Different person")
        assertNull(ShellCallMetadataJournal.selectCellularTaggedVoip(
            100_000L, CallDirection.INCOMING, listOf(first, second)))
    }

    @Test fun oldLateOppositeOrAnonymousCandidatesAreRejected() {
        val select = ShellCallMetadataJournal::selectCellularTaggedVoip
        assertNull(select(100_000L, CallDirection.INCOMING, listOf()))
        assertNull(select(100_000L, CallDirection.INCOMING,
            listOf(candidate(whenMs = 200_000L))))
        assertNull(select(100_000L, CallDirection.OUTGOING, listOf(candidate())))
        assertNull(select(100_000L, CallDirection.UNKNOWN, listOf(candidate())))
        assertNull(select(100_000L, CallDirection.INCOMING,
            listOf(candidate(name = null, number = null))))
        assertNull(select(100_000L, CallDirection.INCOMING,
            listOf(candidate(pkg = null))))
        assertEquals(candidate(name = null, number = "+23051234567"),
            select(100_000L, CallDirection.INCOMING,
                listOf(candidate(name = null, number = "+23051234567"))))
    }
    @Test fun genericShellSimFileUsesUniqueVerifiedSimNameOrNumber() {
        val fromTelecom = candidate(
            key = "telecom-sim-1", kind = CallDirection.INCOMING,
            flow = CallDirection.INCOMING, pkg = null,
            name = "Saved SIM contact", media = "VOICE")
        assertEquals(fromTelecom, ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.INCOMING, listOf(fromTelecom)))
        val justNumber = fromTelecom.copy(name = null, number = "+23051234567")
        assertEquals(justNumber, ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.INCOMING, listOf(justNumber)))
    }

    @Test fun explicitlyPrivateSimNamePersistsButMissingNumberIsNotAutomaticallyPrivate() {
        val restricted = candidate(kind = CallDirection.INCOMING, flow = CallDirection.INCOMING,
            pkg = null, name = "Private number", number = null)
        assertEquals(restricted, ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.INCOMING, listOf(restricted)))
        assertNull(ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.INCOMING,
            listOf(restricted.copy(name = null))))
    }

    @Test fun competingVoipCallPreventsGenericSimAttribution() {
        val sim = candidate(key = "sim-1", kind = CallDirection.INCOMING,
            pkg = null, name = "SIM caller")
        assertNull(ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.INCOMING, listOf(sim, candidate())))
        assertNull(ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.INCOMING, listOf(candidate())))
        assertNull(ShellCallMetadataJournal.selectCellularTaggedSim(
            100_000L, CallDirection.OUTGOING, listOf(sim)))
    }
}
