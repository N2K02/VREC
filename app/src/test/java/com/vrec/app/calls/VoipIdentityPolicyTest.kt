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
