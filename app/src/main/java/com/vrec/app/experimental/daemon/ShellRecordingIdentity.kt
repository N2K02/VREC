package com.vrec.app.experimental.daemon

import com.vrec.app.recording.CallDirection

/** Parse only VRec's known shell filenames. No guesswork from arbitrary suffixes. */
data class ShellRecordingIdentity(
    val startedAt: Long,
    val direction: CallDirection,
    val appPackage: String? = null,
) {
    companion object {
        private val pattern = Regex(
            "^vrec_rec_([0-9]{12,15})_(in|out|voip-([A-Za-z0-9_.]{1,200}))\\.dat$"
        )

        fun fromName(filename: String): ShellRecordingIdentity? {
            val match = pattern.matchEntire(filename) ?: return null
            val millis = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
            val kind = match.groupValues[2]
            val pkg = match.groupValues[3].ifBlank { null }
            return when (kind) {
                "in" -> ShellRecordingIdentity(millis, CallDirection.INCOMING)
                "out" -> ShellRecordingIdentity(millis, CallDirection.OUTGOING)
                else -> if (pkg in EnhancedVoipPolicy.supportedPackages)
                    ShellRecordingIdentity(millis, CallDirection.VOIP, pkg) else null
            }
        }
    }
}

/** Bind each manual shell start to its selected call, never a competing watcher. */
internal object ManualCaptureIdentityPolicy {
    fun matches(file: String, expectedPackage: String?, expectedDirection: CallDirection,
                isVoip: Boolean): Boolean {
        val actual = ShellRecordingIdentity.fromName(file) ?: return false
        return if (isVoip) actual.direction == CallDirection.VOIP &&
            expectedPackage != null && actual.appPackage == expectedPackage
        else actual.appPackage == null &&
            expectedDirection in setOf(CallDirection.INCOMING, CallDirection.OUTGOING) &&
            actual.direction == expectedDirection
    }
}

/** Stable display labels when a VoIP app's package manager entry is not visible. */
object ShellVoipLabels {
    private val labels = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.thoughtcrime.securesms" to "Signal",
        "com.facebook.orca" to "Messenger",
        "com.viber.voip" to "Viber",
        "com.skype.raider" to "Skype",
        "com.google.android.apps.tachyon" to "Google Meet",
    )
    fun forPackage(pkg: String?): String? = labels[pkg]
}
