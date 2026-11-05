package com.vrec.app.calls

import org.junit.Assert.*
import org.junit.Test

class VoipIdentityPolicyTest {
    @Test fun originalGroupTitleReplacesContactAndSurvivesLaterTelecomUpdates() {
        val title = "Shrey et titi"
        assertEquals(title, VoipIdentityPolicy.name("Shreya", "telecom", title, "notification"))
        assertEquals(title, VoipIdentityPolicy.name(title, "notification", "Shreya", "telecom"))
        assertFalse(VoipIdentityPolicy.prefersCandidate(title, "notification", title, "telecom"))
        assertEquals(title, VoipIdentityPolicy.name(title, "notification", null, "notification"))
    }

    @Test fun genericNotificationCannotOverrideTelecomOrEarlierCaller() {
        assertFalse(VoipIdentityPolicy.prefersCandidate(
            "Chachi Renou", "telecom", "WhatsApp call", "notification"))
        assertEquals("Chachi Renou", VoipIdentityPolicy.name(
            "Chachi Renou", "telecom", "WhatsApp call", "notification"))
        assertFalse(VoipIdentityPolicy.prefersCandidate(
            null, null, "Outgoing call", "notification"))
    }

    @Test fun updatedAppTitleCanAddParticipants() {
        assertEquals("Shrey et titi", VoipIdentityPolicy.name("Shrey", "notification", "Shrey et titi", "notification"))
    }

    @Test fun liveAppTitleCanChangeFromOneToGroupAndBack() {
        var current: String? = null
        var source: String? = null
        var history = emptyList<String>()
        listOf(
            "Chachi Renou" to "telecom",
            "Chachi Renou & Mom Orange" to "notification",
            "Chachi Renou & Mom Orange & Vicky" to "accessibility",
            "Chachi Renou & Mom Orange" to "notification",
        ).forEach { (next, nextSource) ->
            assertTrue(VoipIdentityPolicy.prefersCandidate(current, source, next, nextSource))
            current = VoipIdentityPolicy.name(current, source, next, nextSource)
            source = nextSource
            history = VoipIdentityPolicy.updatedNameHistory(history, current)
        }
        assertEquals("Chachi Renou & Mom Orange", current)
        assertEquals(listOf("Chachi Renou", "Chachi Renou & Mom Orange",
            "Chachi Renou & Mom Orange & Vicky", "Chachi Renou & Mom Orange"), history)
    }

    @Test fun genericRepeatedAndStaleTitlesNeverCorruptHistory() {
        var names = emptyList<String>()
        names = VoipIdentityPolicy.updatedNameHistory(names, "Chachi Renou")
        names = VoipIdentityPolicy.updatedNameHistory(names, "chachi renou")
        names = VoipIdentityPolicy.updatedNameHistory(names, "WhatsApp call")
        names = VoipIdentityPolicy.updatedNameHistory(names, "Calling…")
        names = VoipIdentityPolicy.updatedNameHistory(names, null)
        assertEquals(listOf("Chachi Renou"), names)
        assertFalse(VoipIdentityPolicy.prefersCandidate(
            "Chachi Renou & Mom Orange", "notification", "WhatsApp call", "accessibility"))
    }

    @Test fun lowerTrustTelecomUpdatesCannotOverrideLatestGroupTitle() {
        val group = "Chachi Renou & Mom Orange"
        assertEquals(group, VoipIdentityPolicy.name(group, "notification", "Chachi Renou", "telecom"))
        assertEquals(group, VoipIdentityPolicy.name(group, "accessibility", "Chachi Renou", "telecom"))
        assertEquals("Chachi Renou & Mom Orange & Vicky", VoipIdentityPolicy.name(
            group, "notification", "Chachi Renou & Mom Orange & Vicky", "accessibility"))
    }

    @Test fun titleHistoryBoundedButPreservesFirstAndLatest() {
        var names = emptyList<String>()
        repeat(28) { n -> names = VoipIdentityPolicy.updatedNameHistory(names, "Participant $n") }
        assertEquals(20, names.size)
        assertEquals("Participant 0", names.first())
        assertEquals("Participant 27", names.last())
        assertEquals(names, VoipIdentityPolicy.updatedNameHistory(names, "Participant 27"))
    }

    @Test fun appNamesKeepNicknamesPunctuationAndUnicode() {
        listOf("Shrey et titi", "et", "titi", "Friends: A • B", "Équipe 🥳", "X").forEach {
            assertEquals(it, CallMetadataResolver.cleanAppName(it))
        }
        listOf("WhatsApp", "Incoming call", "+230 5123 4567", " ").forEach {
            assertNull(CallMetadataResolver.cleanAppName(it))
        }
    }

    @Test fun numbersKeepAllExplicitParticipantsAndDeduplicateFormatting() {
        assertEquals(listOf("+230 5123 4567", "59876543"), VoipIdentityPolicy.numbers(
            listOf("tel:\u202A+230\u00A05123 4567\u202C", "59876543"), listOf("+23051234567", "5987 6543")))
    }

    @Test fun namesDescriptionsDurationsAndCombinedStringsAreNotNumbers() {
        assertEquals(emptyList<String>(), VoipIdentityPolicy.numbers(listOf(
            "Shrey et titi", "titi", "et", "Call 51234567", "01:23", "123",
            "51234567, 59876543", "1234567890123456789", "sip:51234567@example.com")))
    }

    @Test fun mergesRetainEarlierParticipantsAndAcceptTelCaseInsensitively() {
        assertEquals(listOf("51234567", "59876543"), VoipIdentityPolicy.numbers(
            listOf("51234567"), listOf("TEL:59876543"), emptyList()))
    }
}
