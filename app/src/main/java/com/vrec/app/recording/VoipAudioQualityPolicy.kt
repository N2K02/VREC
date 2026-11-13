package com.vrec.app.recording

/** Conservative two-channel checks for the VoIP PCM layout.
 * Left = call playback, right = microphone. Silence may mean that a
 * participant simply did not speak; this is a warning, never proof of failure.
 */
internal object VoipAudioQualityPolicy {
    fun warning(isVoip: Boolean, diagnostic: String?): String? {
        if (!isVoip || diagnostic == null) return null
        val mic = diagnostic.split(" | ").firstOrNull { it.startsWith("mic=") }
            ?.split(';')?.mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }?.toMap().orEmpty()
        val verdict = diagnostic.substringAfterLast(" | ").trim()
        return when (verdict) {
            "NO_AUDIO", "BOTH_CHANNELS_QUIET" ->
                "VoIP recording saved, but little or no audio was detected. Check the recording and call audio route."
            "LEFT_CHANNEL_QUIET" ->
                "VoIP recording saved, but the call playback channel was quiet. The other person's audio may be missing."
            "RIGHT_CHANNEL_QUIET" -> {
                val detail = when (mic["mic"]) {
                    "SILENCED" -> "Android reported VRec's microphone silenced."
                    "OFF" -> when (mic["reason"]) {
                        "OPEN_FAILED" -> "VRec could not open the microphone."
                        "CALL_MIC_PROTECTED" -> "VRec released its microphone to protect call audio."
                        "READ_FAILED" -> "VRec's microphone reader stopped."
                        "READER_STOP_TIMEOUT" -> "VRec stopped microphone recovery because its previous reader did not close."
                        else -> "VRec's microphone was unavailable."
                    }
                    "ZERO_SAMPLES" -> "VRec received only zero microphone samples; the cause is unconfirmed."
                    "NO_DATA" -> "VRec's microphone stopped delivering samples."
                    else -> ""
                }
                "VoIP recording saved, but the microphone channel was quiet.${if (detail.isEmpty()) "" else " $detail"} Your voice may be missing."
            }
            "CLIPPING" ->
                "VoIP recording saved with high audio levels. Playback may sound distorted."
            else -> null
        }
    }
}
