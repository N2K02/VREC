package com.vrec.app.recording

/** Conservative two-channel checks for the VoIP PCM layout.
 * Left = call playback, right = microphone. Silence may mean that a
 * participant simply did not speak; this is a warning, never proof of failure.
 */
internal object VoipAudioQualityPolicy {
    fun warning(isVoip: Boolean, diagnostic: String?): String? {
        if (!isVoip || diagnostic == null) return null
        val verdict = diagnostic.substringAfterLast(" | ").trim()
        return when (verdict) {
            "NO_AUDIO", "BOTH_CHANNELS_QUIET" ->
                "VoIP recording saved, but little or no audio was detected. Check the recording and call audio route."
            "LEFT_CHANNEL_QUIET" ->
                "VoIP recording saved, but the call playback channel was quiet. The other person's audio may be missing."
            "RIGHT_CHANNEL_QUIET" ->
                "VoIP recording saved, but the microphone channel was quiet. Your voice may be missing."
            "CLIPPING" ->
                "VoIP recording saved with high audio levels. Playback may sound distorted."
            else -> null
        }
    }
}
