package com.vrec.app.experimental.daemon

import com.vrec.app.recording.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedCapturePolicyTest {
    @Test fun voipArmRequiresPermissionCompatibleAndroidAndExplicitSetting() {
        assertTrue(EnhancedVoipPolicy.packages(true, true, 34).contains("com.whatsapp"))
        assertTrue(EnhancedVoipPolicy.packages(true, true, 34).contains("org.telegram.messenger"))
        assertTrue(EnhancedVoipPolicy.packages(true, false, 34).isEmpty())
        assertTrue(EnhancedVoipPolicy.packages(false, true, 34).isEmpty())
        assertTrue(EnhancedVoipPolicy.packages(true, true, 33).isEmpty())
        assertFalse(EnhancedVoipPolicy.isShellVoipCall(true, true, 34, "unknown.app"))
        assertFalse(EnhancedVoipPolicy.isShellVoipCall(true, true, 34, null))
    }

    @Test fun simSourcesHaveStableWireValuesAndSafeFallback() {
        assertEquals(0, SimCaptureSource.VOICE_CALL.wireValue)
        assertEquals(1, SimCaptureSource.UPLINK.wireValue)
        assertEquals(2, SimCaptureSource.DOWNLINK.wireValue)
        assertEquals(3, SimCaptureSource.DUAL.wireValue)
        assertEquals(SimCaptureSource.VOICE_CALL, SimCaptureSource.from("bad-option"))
    }

    @Test fun importedShellVoipPreservesAppIdentityAndType() {
        val recording = ShellRecordingIdentity.fromName(
            "vrec_rec_1792945016544_voip-com.whatsapp.dat")
        assertEquals(CallDirection.VOIP, recording?.direction)
        assertEquals("com.whatsapp", recording?.appPackage)
        assertEquals("WhatsApp", ShellVoipLabels.forPackage(recording?.appPackage))
        assertEquals(CallDirection.VOIP, ShellRecordingIdentity.fromName(
            "vrec_rec_1792945016544_voip-org.telegram.messenger.dat")?.direction)
    }

    @Test fun importedShellCellularKeepsDirectionAndRejectsUnrecognizedVoip() {
        assertEquals(CallDirection.INCOMING, ShellRecordingIdentity.fromName(
            "vrec_rec_1792945016544_in.dat")?.direction)
        assertEquals(CallDirection.OUTGOING, ShellRecordingIdentity.fromName(
            "vrec_rec_1792945016544_out.dat")?.direction)
        assertNull(ShellRecordingIdentity.fromName("vrec_rec_1792945016544_voip-malicious.app.dat"))
        assertNull(ShellRecordingIdentity.fromName("vrec_rec_1792945016544_voip-../other.dat"))
        assertNull(ShellRecordingIdentity.fromName("vrec_rec_1792945016544_weird.dat"))
    }
}
