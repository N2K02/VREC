package com.vrec.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingTypeResolverTest {
    @Test fun explicitCaptureTypeOverridesCurrentSettingsAndFileExtension() {
        assertEquals(RecordingType.ADB, RecordingTypeResolver.resolve(
            "ADB", "Voice call line", "recording.m4a"))
        assertEquals(RecordingType.STANDARD, RecordingTypeResolver.resolve(
            "STANDARD", "Enhanced shell capture", "vrec_rec_1792945016544_in.ogg"))
    }

    @Test fun legacyEnhancedCaptureIsRecognizedFromShellMetadata() {
        assertEquals(RecordingType.ADB, RecordingTypeResolver.resolve(
            null, "Enhanced shell capture", "old.ogg"))
        assertEquals(RecordingType.ADB, RecordingTypeResolver.resolve(
            null, "Shell VOICE_CALL", "old.ogg"))
        assertEquals(RecordingType.ADB, RecordingTypeResolver.resolve(
            null, null, "vrec_rec_1792945016544_in.ogg"))
        assertEquals(RecordingType.ADB, RecordingTypeResolver.resolve(
            null, null, "vrec_rec_1792945016544_out.ogg"))
    }

    @Test fun standardFallbackAndManualFilesStayStandard() {
        assertEquals(RecordingType.STANDARD, RecordingTypeResolver.resolve(
            "STANDARD", "Communication mic", "20261025_202514_IN.m4a"))
        assertEquals(RecordingType.STANDARD, RecordingTypeResolver.resolve(
            null, "Voice recognition", "20261025_202514_IN.m4a"))
        assertEquals(RecordingType.STANDARD, RecordingTypeResolver.resolve(
            null, null, "20261025_202514_IN.m4a"))
    }

    @Test fun unknownAudioMustNotBeMislabelledAsAdb() {
        assertNull(RecordingTypeResolver.resolve(null, null, "imported.ogg"))
        assertNull(RecordingTypeResolver.resolve(null, null, "vrec_rec_abc_in.ogg"))
    }
}
