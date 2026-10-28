package com.vrec.app.ui

import com.vrec.app.recording.CallDirection
import java.util.Locale

/**
 * Human-readable recording list details. Unknown metadata stays unknown rather than
 * misidentifying an app, a SIM, or the direction of a VoIP call.
 */
internal object RecordingListLabels {
    enum class SourceKind { APP, PHONE, UNKNOWN }

    /** Only a confirmed VIDEO signal renders the camera badge. */
    enum class MediaBadge { VIDEO, CALL }
    fun mediaBadge(mediaType: String?): MediaBadge =
        if (mediaType?.uppercase(Locale.ROOT) == "VIDEO") MediaBadge.VIDEO else MediaBadge.CALL

    /** Semantic source colors; actual Material colors are selected by the UI theme. */
    enum class SourceAccent { GREEN, BLUE, VIOLET, NEUTRAL }

    fun sourceAccent(kind: SourceKind, packageName: String?): SourceAccent =
        when (kind) {
            SourceKind.PHONE -> SourceAccent.BLUE
            SourceKind.UNKNOWN -> SourceAccent.NEUTRAL
            SourceKind.APP -> when (packageName?.trim()?.lowercase(Locale.ROOT)) {
                "com.whatsapp", "com.whatsapp.w4b" -> SourceAccent.GREEN
                "org.telegram.messenger", "org.thoughtcrime.securesms",
                "com.facebook.orca", "com.google.android.apps.tachyon" -> SourceAccent.BLUE
                else -> SourceAccent.VIOLET
            }
        }

    /**
     * A call can be incoming/outgoing and still be VoIP. Prefer explicit call
     * metadata and known VoIP packages; never label an ordinary SIM call as
     * WhatsApp just because its dialer package was stored.
     */
    fun sourceKind(
        direction: CallDirection,
        sourcePackage: String?,
        callType: String?,
    ): SourceKind {
        val pkg = sourcePackage?.trim()?.lowercase(Locale.ROOT)
        if (direction == CallDirection.VOIP ||
            callType?.equals("VOIP", ignoreCase = true) == true ||
            pkg in setOf(
                "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
                "org.thoughtcrime.securesms", "com.facebook.orca",
                "com.viber.voip", "com.skype.raider", "com.google.android.apps.tachyon",
            )
        ) return SourceKind.APP
        if (direction == CallDirection.INCOMING || direction == CallDirection.OUTGOING) {
            return SourceKind.PHONE
        }
        return if (!pkg.isNullOrBlank()) SourceKind.APP else SourceKind.UNKNOWN
    }

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

    /** Preserve VoIP identity separately from the direction of the call. */
    fun flowDirection(direction: CallDirection, callFlowDirection: String?): CallDirection =
        when (callFlowDirection?.uppercase(Locale.ROOT)) {
            CallDirection.INCOMING.name -> CallDirection.INCOMING
            CallDirection.OUTGOING.name -> CallDirection.OUTGOING
            CallDirection.UNKNOWN.name -> CallDirection.UNKNOWN
            else -> when (direction) {
                CallDirection.INCOMING, CallDirection.OUTGOING -> direction
                else -> CallDirection.UNKNOWN
            }
        }

    fun direction(direction: CallDirection, callFlowDirection: String?): String =
        when (flowDirection(direction, callFlowDirection)) {
            CallDirection.INCOMING -> "Incoming"
            CallDirection.OUTGOING -> "Outgoing"
            else -> if (direction == CallDirection.VOIP) "VoIP" else "Unknown direction"
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
