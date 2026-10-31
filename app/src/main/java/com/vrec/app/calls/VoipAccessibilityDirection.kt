package com.vrec.app.calls

import com.vrec.app.recording.CallDirection

/** Trust only an explicit call-direction label from a recognized, active VoIP call screen. */
internal object VoipAccessibilityDirection {
    fun fromCallLabels(labels: Collection<String>): CallDirection? {
        val directions = labels.mapNotNull { label ->
            VoipNotificationDirection.fromSignal(0, "UNKNOWN", label, null)
                .takeIf { it == CallDirection.INCOMING || it == CallDirection.OUTGOING }
        }.distinct()
        // Conflicting labels must not produce fabricated incoming/outgoing metadata.
        return directions.singleOrNull()
    }
}
