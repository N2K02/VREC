package com.vrec.app.recording

import org.junit.Assert.*
import org.junit.Test

class VoipAudioQualityPolicyTest {
    @Test fun healthyVoipDoesNotWarn() {
        assertNull(VoipAudioQualityPolicy.warning(true,
            "VOIP_PLAYBACK_MIC | PCM diagnostic: frames=48000 | AUDIO_DETECTED"))
    }
    @Test fun missingPlaybackChannelIsFlaggedButNotMisidentifiedAsCertain() {
        val warning = VoipAudioQualityPolicy.warning(true,
            "VOIP_PLAYBACK_MIC | PCM diagnostic: frames=48000 | LEFT_CHANNEL_QUIET")
        assertTrue(warning!!.contains("may be missing"))
        assertTrue(warning.contains("other person"))
    }
    @Test fun missingMicrophoneChannelIsFlagged() {
        assertTrue(VoipAudioQualityPolicy.warning(true,
            "VOIP_PLAYBACK_MIC | PCM diagnostic | RIGHT_CHANNEL_QUIET")!!
            .contains("Your voice may be missing"))
    }
    @Test fun silenceIsNotWronglyAssignedToCellularCall() {
        assertNull(VoipAudioQualityPolicy.warning(false,
            "VOICE_CALL | PCM diagnostic | LEFT_CHANNEL_QUIET"))
        assertNull(VoipAudioQualityPolicy.warning(true, null))
    }
    @Test fun totallyQuietRecordingAndClippingAreWarned() {
        assertNotNull(VoipAudioQualityPolicy.warning(true, "VOIP_PLAYBACK_MIC | NO_AUDIO"))
        assertNotNull(VoipAudioQualityPolicy.warning(true, "VOIP_PLAYBACK_MIC | CLIPPING"))
    }
}
