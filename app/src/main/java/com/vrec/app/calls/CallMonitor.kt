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
    private var activeVoipApp: String? = null
    private var connectedAt: Long? = null

    @Synchronized
    fun onSimState(context: Context, state: String?, number: String?) {
        if (!Prefs.recordingEnabled(context)) {
            if (recording != Kind.NONE) stop(context)
            if (state == "IDLE") resetDetection()
            return
        }
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
                startIfArmed(context, Kind.SIM, direction, peer, phoneNumber = pendingIncomingNumber, callConnectedAt = System.currentTimeMillis())
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
        if (!Prefs.recordingEnabled(context) || !Prefs.recordVoip(context)) return
        if (active) {
            val peer = caller?.takeIf { it.isNotBlank() }?.let { "$it ($appLabel)" } ?: appLabel
            if (recording == Kind.VOIP && activeVoipApp == appLabel) {
                RecordingService.updateVoipPeer(context, peer)
                return
            }
            if (recording != Kind.NONE) return // SIM or another VoIP call already recording
            activeVoipApp = appLabel
            startIfArmed(context, Kind.VOIP, CallDirection.VOIP, peer, callConnectedAt = System.currentTimeMillis())
        } else {
            if (recording == Kind.VOIP && activeVoipApp == appLabel) stop(context)
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

    /**
     * Persists the Home-screen master switch. Turning it off immediately stops any active
     * recording and clears call-detection state so a later enable starts cleanly.
     */
    @Synchronized
    fun setRecordingEnabled(context: Context, enabled: Boolean) {
        Prefs.setRecordingEnabled(context, enabled)
        if (!enabled) {
            recording = Kind.NONE
            activeVoipApp = null
            connectedAt = null
            resetDetection()
            if (RecordingService.state.value !is RecordingService.State.Idle) {
                RecordingService.stop(context.applicationContext, System.currentTimeMillis())
            }
        }
        Log.i("Recording function " + if (enabled) "enabled" else "disabled")
    }

    private fun resetDetection() {
        sawRinging = false
        pendingIncomingNumber = null
    }

    private fun startIfArmed(
        context: Context,
        kind: Kind,
        direction: CallDirection,
        peer: String?,
        phoneNumber: String? = null,
        force: Boolean = false,
        callConnectedAt: Long? = null,
    ) {
        if (!Prefs.recordingEnabled(context)) {
            Log.i("Recording function is off; not starting a recording")
            return
        }
        if (!force && !Prefs.autoRecord(context)) {
            Log.i("Auto-record is off; not recording this call")
            return
        }
        recording = kind
        connectedAt = callConnectedAt
        RecordingService.start(context.applicationContext, direction, peer, phoneNumber, callConnectedAt)
    }

    private fun stop(context: Context) {
        recording = Kind.NONE
        activeVoipApp = null
        connectedAt = null
        RecordingService.stop(context.applicationContext, System.currentTimeMillis())
    }
}
