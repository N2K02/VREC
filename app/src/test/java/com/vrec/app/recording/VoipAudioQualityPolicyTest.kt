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
    @Test fun micEvidenceExplainsQuietChannelWithoutGuessing() {
        val cases = mapOf(
            "mic=SILENCED;source=MIC" to "Android reported",
            "mic=OFF;reason=OPEN_FAILED" to "could not open",
            "mic=OFF;reason=CALL_MIC_PROTECTED" to "protect call audio",
            "mic=OFF;reason=READ_FAILED" to "reader stopped",
            "mic=OFF;reason=READER_STOP_TIMEOUT" to "did not close",
            "mic=ZERO_SAMPLES" to "cause is unconfirmed",
            "mic=NO_DATA" to "stopped delivering",
        )
        cases.forEach { (mic, expected) ->
            assertTrue(VoipAudioQualityPolicy.warning(true,
                "VOIP_PLAYBACK_MIC | $mic | RIGHT_CHANNEL_QUIET")!!.contains(expected))
        }
    }
    @Test fun recoveredMicrophoneDoesNotTurnHistoricalFailuresIntoWarnings() {
        assertNull(VoipAudioQualityPolicy.warning(true,
            "VOIP_PLAYBACK_MIC | mic=ON;events=MIC:POLICY_SILENCED | AUDIO_DETECTED"))
        val warning = VoipAudioQualityPolicy.warning(true,
            "VOIP_PLAYBACK_MIC | mic=ON;reason=POLICY_SILENCED | RIGHT_CHANNEL_QUIET")!!
        assertFalse(warning.contains("Android reported"))
    }
}
