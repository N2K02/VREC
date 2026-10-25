package com.vrec.app.ui

import com.vrec.app.recording.CallDirection
import java.util.Locale

/**
 * Human-readable recording list details. Unknown metadata stays unknown rather than
 * misidentifying an app, a SIM, or the direction of a VoIP call.
 */
internal object RecordingListLabels {
    fun contact(
        savedName: String?,
        lookedUpName: String?,
        peer: String?,
        phoneNumber: String?,
    ): String = listOf(
        savedName,
        lookedUpName?.takeUnless { it.trim() == phoneNumber?.trim() },
        peer,
        phoneNumber,
    ).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: "Unknown contact"

    fun direction(direction: CallDirection, callFlowDirection: String?): String = when (direction) {
        CallDirection.INCOMING -> "Incoming"
        CallDirection.OUTGOING -> "Outgoing"
        CallDirection.VOIP -> when (callFlowDirection?.uppercase(Locale.ROOT)) {
            "INCOMING" -> "Incoming"
            "OUTGOING" -> "Outgoing"
            else -> "VoIP"
        }
        CallDirection.UNKNOWN -> "Unknown direction"
    }

    fun source(
        direction: CallDirection,
        appName: String?,
        simSlot: Int?,
        simLabel: String?,
        carrier: String?,
    ): String {
        if (direction == CallDirection.INCOMING || direction == CallDirection.OUTGOING) {
            val sim = simSlot?.takeIf { it > 0 }?.let { "SIM $it" } ?: "SIM"
            val network = listOf(carrier, simLabel).firstNotNullOfOrNull {
                it?.trim()?.takeIf { value ->
                    value.isNotEmpty() && !value.equals(sim, ignoreCase = true)
                }
            }
            return if (network == null) sim else "$sim ($network)"
        }
        return appName?.trim()?.takeIf(String::isNotEmpty)
            ?: if (direction == CallDirection.VOIP) "VoIP app" else "Unknown source"
    }
}
