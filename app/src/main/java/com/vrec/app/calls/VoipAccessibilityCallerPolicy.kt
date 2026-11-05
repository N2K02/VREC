package com.vrec.app.calls

import java.util.Locale

/** Only labels from a verified, active call screen; fail closed if ambiguous. */
internal object VoipAccessibilityCallerPolicy {
    private val controls = setOf(
        "mute", "unmute", "speaker", "speakerphone", "video", "voice", "camera",
        "switch camera", "switch to video", "switch to voice", "add participant",
        "add call", "end call", "hang up", "disconnect", "disconnect call",
        "record", "record call", "hold", "resume", "pause", "more", "back",
        "return to call", "bluetooth", "keypad", "call", "you",
        "connecting", "connected", "ringing", "encrypted", "end-to-end encrypted",
        "end to end encrypted", "tap to return", "poor connection",
    )
    private val elapsed = Regex("^(?:\\d{1,2}:){1,2}\\d{2}$")

    fun uniqueCallerName(labels: Collection<String>): String? {
        val candidates = labels.mapNotNull(CallMetadataResolver::cleanAppName)
            .filter { candidate ->
                val value = candidate.lowercase(Locale.ROOT)
                value !in controls &&
                    !elapsed.matches(value) &&
                    !value.contains("encrypted") &&
                    !value.startsWith("tap to ") &&
                    !value.startsWith("swipe to ") &&
                    !value.startsWith("return to ") &&
                    !value.startsWith("end call") &&
                    !value.startsWith("switch to ") &&
                    !value.startsWith("add participant") &&
                    !value.startsWith("poor connection") &&
                    !value.startsWith("call duration") &&
                    !value.startsWith("call time")
            }.distinctBy { it.lowercase(Locale.ROOT) }
        return candidates.singleOrNull()
    }
}
