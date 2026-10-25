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
        fileName: String? = null,
    ): String = listOf(
        savedName,
        lookedUpName?.takeUnless { it.trim() == phoneNumber?.trim() },
        peer,
        phoneNumber,
        legacyFilenameContact(fileName),
    ).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) } ?: "Unknown contact"

    private fun legacyFilenameContact(fileName: String?): String? {
        val name = fileName ?: return null
        val raw = Regex("^\\d{8}_\\d{6}_(?:IN|OUT|VOIP)_(.+)\\.(?:m4a|ogg)$", RegexOption.IGNORE_CASE)
            .matchEntire(name)?.groupValues?.get(1) ?: return null
        return raw.replace('_', ' ').trim().takeIf { it.isNotBlank() }
    }

    fun direction(direction: CallDirection, callFlowDirection: String?): String = when (direction) {
        CallDirection.INCOMING -> "Incoming"
        CallDirection.OUTGOING -> "Outgoing"
        CallDirection.VOIP, CallDirection.UNKNOWN -> when (callFlowDirection?.uppercase(Locale.ROOT)) {
            "INCOMING" -> "Incoming"
            "OUTGOING" -> "Outgoing"
            else -> if (direction == CallDirection.VOIP) "VoIP" else "Unknown direction"
        }
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
