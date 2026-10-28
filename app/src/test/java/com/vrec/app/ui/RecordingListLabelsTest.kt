package com.vrec.app.ui

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingListLabelsTest {
    @Test fun prefersSavedOrResolvedContactBeforeUnverifiedPeerOrNumber() {
        assertEquals("Saved name", RecordingListLabels.contact("Saved name", "Phonebook", "Caller", "+2301234567"))
        assertEquals("Phonebook", RecordingListLabels.contact(null, "Phonebook", "Caller", "+2301234567"))
        assertEquals("Caller", RecordingListLabels.contact(null, "+2301234567", "Caller", "+2301234567"))
        assertEquals("+2301234567", RecordingListLabels.contact(null, null, null, "+2301234567"))
        assertEquals("Unknown caller", RecordingListLabels.contact(" ", "", null, null))
        assertEquals("Sonu orange", RecordingListLabels.contact(null, null, null, null, "20261025_202514_VOIP_Sonu_orange.m4a"))
        assertEquals("Unknown caller", RecordingListLabels.contact(null, null, null, null, "vrec_rec_1792945016544_in.ogg"))
    }

    @Test fun determinesDirectionWithoutGuessingForLegacyVoip() {
        assertEquals("Incoming", RecordingListLabels.direction(CallDirection.INCOMING, null))
        assertEquals("Outgoing", RecordingListLabels.direction(CallDirection.OUTGOING, null))
        assertEquals("Incoming", RecordingListLabels.direction(CallDirection.VOIP, "incoming"))
        assertEquals("Outgoing", RecordingListLabels.direction(CallDirection.VOIP, "OUTGOING"))
        assertEquals("VoIP", RecordingListLabels.direction(CallDirection.VOIP, null))
        assertEquals("VoIP", RecordingListLabels.direction(CallDirection.VOIP, "UNKNOWN"))
        assertEquals("Unknown direction", RecordingListLabels.direction(CallDirection.UNKNOWN, null))
        assertEquals("Outgoing", RecordingListLabels.direction(CallDirection.UNKNOWN, "OUTGOING"))
    }

    @Test fun cellularSourcesPrioritizeVerifiedSimMetadata() {
        assertEquals("SIM 1 (Orange)", RecordingListLabels.source(CallDirection.INCOMING, "Phone", 1, "My SIM", "Orange"))
        assertEquals("SIM 2 (Orange)", RecordingListLabels.source(CallDirection.OUTGOING, null, 2, "Orange", null))
        assertEquals("SIM", RecordingListLabels.source(CallDirection.INCOMING, null, null, null, null))
        assertEquals("SIM 1", RecordingListLabels.source(CallDirection.INCOMING, null, 1, "SIM 1", null))
    }

    @Test fun voipAndUnknownSourcesUseAppWithoutClaimingCellularSim() {
        assertEquals("WhatsApp", RecordingListLabels.source(CallDirection.VOIP, "WhatsApp", null, null, null))
        assertEquals("VoIP app", RecordingListLabels.source(CallDirection.VOIP, null, null, null, null))
        assertEquals("Signal", RecordingListLabels.source(CallDirection.UNKNOWN, "Signal", null, null, null))
        assertEquals("Unknown source", RecordingListLabels.source(CallDirection.UNKNOWN, null, null, null, null))
    }
    @Test fun sourceBadgesMatchSavedAppInsteadOfAssumingEveryIncomingCallIsSim() {
        val source = RecordingListLabels::sourceKind
        assertEquals(RecordingListLabels.SourceKind.APP,
            source(CallDirection.VOIP, "com.whatsapp", "VOIP"))
        assertEquals(RecordingListLabels.SourceKind.APP,
            source(CallDirection.INCOMING, "com.whatsapp", null))
        assertEquals(RecordingListLabels.SourceKind.APP,
            source(CallDirection.OUTGOING, "org.telegram.messenger", null))
        assertEquals(RecordingListLabels.SourceKind.APP,
            source(CallDirection.OUTGOING, "com.example.newvoip", "VoIP"))
        assertEquals(RecordingListLabels.SourceKind.PHONE,
            source(CallDirection.INCOMING, "com.android.dialer", null))
        assertEquals(RecordingListLabels.SourceKind.PHONE,
            source(CallDirection.OUTGOING, null, null))
    }

    @Test fun missingLegacySourceMetadataKeepsNeutralFallbackInsteadOfInventingApp() {
        val source = RecordingListLabels::sourceKind
        assertEquals(RecordingListLabels.SourceKind.APP,
            source(CallDirection.VOIP, null, null))
        assertEquals(RecordingListLabels.SourceKind.APP,
            source(CallDirection.UNKNOWN, "com.example.app", null))
        assertEquals(RecordingListLabels.SourceKind.UNKNOWN,
            source(CallDirection.UNKNOWN, null, null))
        assertEquals(RecordingListLabels.SourceKind.UNKNOWN,
            source(CallDirection.UNKNOWN, " ", null))
        assertEquals("VoIP", RecordingListLabels.direction(CallDirection.VOIP, "UNKNOWN"))
        assertEquals("Incoming", RecordingListLabels.direction(CallDirection.VOIP, "incoming"))
        assertEquals("Outgoing", RecordingListLabels.direction(CallDirection.VOIP, "outgoing"))
    }

    @Test fun verifiedFlowOverridesShellDirectionAndVoipRemainsVoipSource() {
        assertEquals("Incoming", RecordingListLabels.direction(CallDirection.OUTGOING, "INCOMING"))
        assertEquals("Outgoing", RecordingListLabels.direction(CallDirection.INCOMING, "OUTGOING"))
        assertEquals(CallDirection.INCOMING, RecordingListLabels.flowDirection(
            CallDirection.VOIP, "incoming"))
        assertEquals(CallDirection.OUTGOING, RecordingListLabels.flowDirection(
            CallDirection.VOIP, "OUTGOING"))
        assertEquals(CallDirection.INCOMING, RecordingListLabels.flowDirection(
            CallDirection.OUTGOING, "INCOMING"))
        assertEquals(CallDirection.OUTGOING, RecordingListLabels.flowDirection(
            CallDirection.OUTGOING, null))
        assertEquals(CallDirection.UNKNOWN, RecordingListLabels.flowDirection(
            CallDirection.VOIP, null))
        assertEquals(CallDirection.UNKNOWN, RecordingListLabels.flowDirection(
            CallDirection.INCOMING, "UNKNOWN"))
        assertEquals("Unknown direction", RecordingListLabels.direction(
            CallDirection.OUTGOING, "UNKNOWN"))
        assertEquals(RecordingListLabels.SourceKind.APP, RecordingListLabels.sourceKind(
            CallDirection.VOIP, "com.whatsapp", "VOIP"))
    }
    @Test fun sourceBadgeAccentsRespectAppIdentityAndKeepUnknownNeutral() {
        val app = RecordingListLabels.SourceKind.APP
        val phone = RecordingListLabels.SourceKind.PHONE
        val unknown = RecordingListLabels.SourceKind.UNKNOWN
        assertEquals(RecordingListLabels.SourceAccent.GREEN,
            RecordingListLabels.sourceAccent(app, "com.whatsapp"))
        assertEquals(RecordingListLabels.SourceAccent.GREEN,
            RecordingListLabels.sourceAccent(app, "com.whatsapp.w4b"))
        assertEquals(RecordingListLabels.SourceAccent.BLUE,
            RecordingListLabels.sourceAccent(app, "org.telegram.messenger"))
        assertEquals(RecordingListLabels.SourceAccent.BLUE,
            RecordingListLabels.sourceAccent(app, "org.thoughtcrime.securesms"))
        assertEquals(RecordingListLabels.SourceAccent.VIOLET,
            RecordingListLabels.sourceAccent(app, "com.viber.voip"))
        assertEquals(RecordingListLabels.SourceAccent.VIOLET,
            RecordingListLabels.sourceAccent(app, "com.example.unknownvoip"))
        assertEquals(RecordingListLabels.SourceAccent.BLUE,
            RecordingListLabels.sourceAccent(phone, null))
        assertEquals(RecordingListLabels.SourceAccent.NEUTRAL,
            RecordingListLabels.sourceAccent(unknown, null))
    }
    @Test fun videoCallUsesCameraBadgeAudioAndLegacyCallsUseCallBadge() {
        assertEquals(RecordingListLabels.MediaBadge.VIDEO,
            RecordingListLabels.mediaBadge("VIDEO"))
        assertEquals(RecordingListLabels.MediaBadge.VIDEO,
            RecordingListLabels.mediaBadge("video"))
        assertEquals(RecordingListLabels.MediaBadge.CALL,
            RecordingListLabels.mediaBadge("VOICE"))
        assertEquals(RecordingListLabels.MediaBadge.CALL,
            RecordingListLabels.mediaBadge(null))
        assertEquals(RecordingListLabels.MediaBadge.CALL,
            RecordingListLabels.mediaBadge("UNKNOWN"))
    }
    @Test fun savedNameThenNumberThenOnlyExplicitPrivateNumberAreValidCallerFallbacks() {
        assertEquals("Saved WhatsApp contact", RecordingListLabels.contact(
            "Saved WhatsApp contact", null, "WhatsApp", "+23051234567"))
        assertEquals("+23051234567", RecordingListLabels.contact(
            null, null, "WhatsApp", "+23051234567"))
        assertEquals("Private number", RecordingListLabels.contact(
            "Private number", null, "SIM", null))
        assertEquals("Unknown caller", RecordingListLabels.contact(
            null, null, "WhatsApp", null))
        assertEquals("Unknown caller", RecordingListLabels.contact(
            null, null, "SIM", null))
        assertEquals("Saved name", RecordingListLabels.contact(
            "Saved name", "+23051234567", null, "+23051234567"))
    }
}
