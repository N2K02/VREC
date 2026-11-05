package com.vrec.app.calls

import org.junit.Assert.*
import org.junit.Test

class CallMetadataResolverTest {
    @Test fun rejectsPlaceholderAndNotificationNarrative() {
        assertNull(CallMetadataResolver.cleanName("Incoming call"))
        assertNull(CallMetadataResolver.cleanName("WhatsApp"))
        assertNull(CallMetadataResolver.cleanName("Call in progress"))
        assertNull(CallMetadataResolver.cleanName("From: John"))
    }

    @Test fun genericWhatsappTitlesNeverBecomeCallerNames() {
        listOf("WhatsApp call", "WhatsApp voice call", "WhatsApp video call",
            "WhatsApp Business call", "Incoming call from Chachi Renou",
            "Outgoing voice call", "Calling Chachi Renou", "Dialing…",
            "Video call", "Audio call", "Call in progress", "Group call",
            "WhatsApp group call", "Conference call").forEach {
            assertNull("Unexpected identity from: $it", CallMetadataResolver.cleanAppName(it))
        }
        assertEquals("Chachi Renou", CallMetadataResolver.cleanAppName("Chachi Renou"))
        assertEquals("Friends: A • B", CallMetadataResolver.cleanAppName("Friends: A • B"))
    }

    @Test fun verifiedCallScreenNeedsOneDistinctCaller() {
        assertEquals("Chachi Renou", VoipAccessibilityCallerPolicy.uniqueCallerName(
            listOf("WhatsApp", "WhatsApp call", "Chachi Renou", "Mute", "Speaker",
                "End call", "00:08", "End-to-end encrypted")))
        assertEquals("Chachi Renou", VoipAccessibilityCallerPolicy.uniqueCallerName(
            listOf("Chachi Renou", "chachi renou", "Speaker")))
        assertNull(VoipAccessibilityCallerPolicy.uniqueCallerName(
            listOf("Chachi Renou", "Another caller", "End call")))
        assertNull(VoipAccessibilityCallerPolicy.uniqueCallerName(
            listOf("WhatsApp call", "Mute", "Speaker", "Calling…", "End call")))
    }

    @Test fun recognizesGroupTitleAlongsideExplicitParticipantLabels() {
        assertEquals("Chachi Renou & Mom Orange",
            VoipAccessibilityCallerPolicy.uniqueCallerName(listOf(
                "Mute", "End call", "Add people", "Participants",
                "Chachi Renou & Mom Orange",
                "Chachi Renou", "Mom Orange", "00:12")))
        assertEquals("Vicky Petit Sable Orange & Mom",
            VoipAccessibilityCallerPolicy.uniqueCallerName(listOf(
                "Vicky Petit Sable Orange & Mom", "Vicky Petit Sable Orange", "Mom", "End call")))
        assertEquals("Mom et Vicky",
            VoipAccessibilityCallerPolicy.uniqueCallerName(listOf(
                "Mom et Vicky", "Mom", "Vicky", "End call")))
    }

    @Test fun rejectsAmbiguousGroupsAndParallelCallIdentities() {
        assertNull(VoipAccessibilityCallerPolicy.uniqueCallerName(listOf(
            "Chachi Renou & Mom Orange", "Chachi Renou", "Mom Orange", "Other caller")))
        assertNull(VoipAccessibilityCallerPolicy.uniqueCallerName(listOf(
            "Chachi Renou", "Mom Orange", "End call")))
        assertNull(VoipAccessibilityCallerPolicy.uniqueCallerName(listOf(
            "Mom & Vicky", "Mom & Chachi", "End call")))
    }

    @Test fun acceptsActualNamesWithoutTreatingThemAsPhoneNumbers() {
        assertEquals("John Smith", CallMetadataResolver.cleanName("  John   Smith  "))
        assertNull(CallMetadataResolver.cleanName("+230 5123 4567"))
    }

    @Test fun numbersRequireDigitsAndExplicitPhoneShape() {
        assertEquals("+230 5123 4567", CallMetadataResolver.directNumber("tel:+230 5123 4567"))
        assertNull(CallMetadataResolver.directNumber("John 12345678"))
        assertNull(CallMetadataResolver.directNumber("123"))
    }
}
