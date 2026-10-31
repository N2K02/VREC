package com.vrec.app.calls

import com.vrec.app.recording.CallDirection
import java.util.Locale

/**
 * Notification-only calls do not always expose the direction. A ringing
 * incoming CallStyle is evidence of INCOMING; an "ongoing" call isn't evidence
 * of OUTGOING. Only explicit outgoing notification language may set OUTGOING.
 * Telecom's Call.Details.callDirection remains authoritative when available.
 */
internal object VoipNotificationDirection {
    fun fromSignal(
        androidCallType: Int,
        status: String,
        title: String?,
        text: String?,
    ): CallDirection {
        if (androidCallType == 1 || androidCallType == 3 || status == "RINGING") {
            return CallDirection.INCOMING
        }
        val messages = listOfNotNull(title, text).map { it.trim().lowercase(Locale.ROOT) }
        if (messages.any {
                it == "incoming call" || it.startsWith("incoming call ") ||
                    it == "incoming voice call" || it.startsWith("incoming voice call ") ||
                    it == "incoming video call" || it.startsWith("incoming video call ")
            }) return CallDirection.INCOMING
        if (messages.any {
                it == "outgoing call" || it.startsWith("outgoing call ") ||
                    it == "outgoing voice call" || it.startsWith("outgoing voice call ") ||
                    it == "outgoing video call" || it.startsWith("outgoing video call ") ||
                    it == "calling" || it.startsWith("calling ") ||
                    it.startsWith("calling…") || it.startsWith("calling...") ||
                    it == "dialing" || it.startsWith("dialing ") ||
                    it.startsWith("dialing…") || it.startsWith("dialing...")
            }) return CallDirection.OUTGOING
        return CallDirection.UNKNOWN
    }
}
