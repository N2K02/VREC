package com.vrec.app

import android.content.Context
import com.vrec.app.calls.CallMonitor
import com.vrec.app.calls.ShellCallMetadataJournal
import com.vrec.app.experimental.daemon.ShellRecordingIdentity
import com.vrec.app.experimental.daemon.ShellVoipLabels
import com.vrec.app.recording.CallDirection

internal data class LiveRecordingInfo(
    val caller: String,
    val source: String,
    val startedAt: Long?,
)

/**
 * Resolve identity only against the authenticated shell filename and an
 * unambiguous live call or unique time-and-package metadata journal entry.
 */
internal object LiveRecordingInfoResolver {
    fun fromShellFile(context: Context, filename: String?): LiveRecordingInfo {
        val identity = filename?.let(ShellRecordingIdentity::fromName)
            ?: return LiveRecordingInfo("Unknown caller", "ADB shell", null)
        val live = CallMonitor.liveCallerForShell(identity.appPackage, identity.direction)
        val matched = if (identity.appPackage != null)
            ShellCallMetadataJournal.find(context.applicationContext,
                identity.startedAt, identity.appPackage) else null
        val caller = preferredCaller(live?.name, live?.number, matched?.name, matched?.number)
        val source = ShellVoipLabels.forPackage(identity.appPackage)
            ?: when (identity.direction) {
                CallDirection.INCOMING, CallDirection.OUTGOING -> "SIM call"
                else -> "VoIP call"
            }
        return LiveRecordingInfo(caller, source, identity.startedAt)
    }

    internal fun preferredCaller(vararg suggestions: String?): String =
        suggestions.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Unknown caller"
}
