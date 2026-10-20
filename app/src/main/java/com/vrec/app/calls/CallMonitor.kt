package com.vrec.app.calls

import android.content.Context
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.RecordingService

/**
 * The single place that decides when to record. Phone-state (SIM) events and VoIP notification
 * events both funnel here; SIM calls take priority over an overlapping VoIP detection. It starts
 * and stops [RecordingService] and never records two calls at once.
 */
object CallMonitor {

    private enum class Kind { NONE, SIM, VOIP }

    private var recording = Kind.NONE
    private var pendingIncomingNumber: String? = null
    private var sawRinging = false

    @Synchronized
    fun onSimState(context: Context, state: String?, number: String?) {
        when (state) {
            "RINGING" -> {
                sawRinging = true
                if (!number.isNullOrBlank()) pendingIncomingNumber = number
                Log.i("Incoming SIM call" + (number?.let { " from $it" } ?: ""))
            }
            "OFFHOOK" -> {
                if (recording != Kind.NONE) return
                val direction = if (sawRinging) CallDirection.INCOMING else CallDirection.OUTGOING
                val peer = CallInfo.describe(context, pendingIncomingNumber)
                startIfArmed(context, Kind.SIM, direction, peer, phoneNumber = pendingIncomingNumber)
            }
            "IDLE" -> {
                sawRinging = false
                pendingIncomingNumber = null
                if (recording == Kind.SIM) stop(context)
            }
        }
    }

    @Synchronized
    fun onVoipState(context: Context, appLabel: String, caller: String?, active: Boolean) {
        if (!Prefs.recordVoip(context)) return
        if (active) {
            if (recording != Kind.NONE) return // SIM or another VoIP call already recording
            val peer = caller?.takeIf { it.isNotBlank() }?.let { "$it ($appLabel)" } ?: appLabel
            startIfArmed(context, Kind.VOIP, CallDirection.VOIP, peer)
        } else {
            if (recording == Kind.VOIP) stop(context)
        }
    }

    /** Manual record button in the UI, for a call VREC didn't detect. */
    @Synchronized
    fun manualStart(context: Context) {
        if (recording != Kind.NONE) return
        startIfArmed(context, Kind.SIM, CallDirection.UNKNOWN, null, force = true)
    }

    @Synchronized
    fun manualStop(context: Context) {
        if (recording != Kind.NONE) stop(context)
    }

    val isRecording: Boolean get() = recording != Kind.NONE

    private fun startIfArmed(
        context: Context,
        kind: Kind,
        direction: CallDirection,
        peer: String?,
        phoneNumber: String? = null,
        force: Boolean = false,
    ) {
        if (!force && !Prefs.autoRecord(context)) {
            Log.i("Auto-record is off; not recording this call")
            return
        }
        recording = kind
        RecordingService.start(context.applicationContext, direction, peer, phoneNumber)
    }

    private fun stop(context: Context) {
        recording = Kind.NONE
        RecordingService.stop(context.applicationContext)
    }
}
