package com.vrec.app

import android.content.Context
import com.vrec.app.calls.CallMonitor
import com.vrec.app.calls.ShellCallMetadataJournal
import com.vrec.app.experimental.daemon.ShellRecordingIdentity
import com.vrec.app.experimental.daemon.ShellVoipLabels
import com.vrec.app.recording.CallDirection
import com.vrec.app.ui.RecordingListLabels

internal data class LiveRecordingInfo(
    val caller: String,
    val source: String,
    val startedAt: Long?,
    val packageName: String? = null,
    val direction: CallDirection = CallDirection.UNKNOWN,
)

/**
 * Resolve identity only against the authenticated shell filename and an
 * unambiguous live call or unique time-and-package metadata journal entry.
 */
internal object LiveRecordingInfoResolver {
    fun fromShellFile(context: Context, filename: String?): LiveRecordingInfo {
        val identity = filename?.let(ShellRecordingIdentity::fromName)
            ?: return LiveRecordingInfo("Recording in progress", "ADB shell", null)
        val matched = if (identity.appPackage != null)
            ShellCallMetadataJournal.find(context.applicationContext,
                identity.startedAt, identity.appPackage)
        else ShellCallMetadataJournal.findForCellularTaggedVoip(
            context.applicationContext, identity.startedAt, identity.direction)
            ?: ShellCallMetadataJournal.findForCellularTaggedSim(
                context.applicationContext, identity.startedAt, identity.direction)
        val resolvedPackage = matched?.packageName ?: identity.appPackage
        val sourceDirection = if (matched?.direction == CallDirection.VOIP)
            CallDirection.VOIP else identity.direction
        val live = CallMonitor.liveCallerForShell(resolvedPackage, sourceDirection)
        val source = ShellVoipLabels.forPackage(resolvedPackage)
            ?: when (sourceDirection) {
                CallDirection.INCOMING, CallDirection.OUTGOING -> "SIM call"
                else -> "VoIP call"
            }
        val flow = when (matched?.flowDirection) {
            "INCOMING" -> CallDirection.INCOMING
            "OUTGOING" -> CallDirection.OUTGOING
            else -> identity.direction
        }
        val caller = preferredCaller(live?.name, live?.number, matched?.name, matched?.number)
            ?: RecordingListLabels.callSummary(flow, source, matched?.mediaType)
        return LiveRecordingInfo(caller, source, identity.startedAt,
            resolvedPackage, flow)
    }

    internal fun preferredCaller(vararg suggestions: String?): String? =
        suggestions.firstNotNullOfOrNull { suggestion ->
            suggestion?.trim()?.takeIf { it.isNotEmpty() &&
                !it.equals("unknown caller", true) && !it.equals("unknown contact", true) }
        }
}
