package com.vrec.app.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.TelecomManager
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.RecordingService

/**
 * Single authority for recording decisions, regardless of the source of a call signal.
 * Telecom owns a call's lifecycle when available; notifications and Accessibility only
 * enrich its details. Legacy phone-state/notification sessions remain safe fallbacks.
 */
object CallMonitor {
    private enum class Source { TELECOM, SIM, VOIP_NOTIFICATION, MANUAL }
    private data class Session(
        val id: String,
        val source: Source,
        var packageName: String? = null,
        var direction: CallDirection = CallDirection.UNKNOWN,
        var flowDirection: CallDirection = CallDirection.UNKNOWN,
        var number: String? = null,
        var name: String? = null,
        var numberSource: String = "unknown",
        var nameSource: String = "unknown",
        var accountId: String? = null,
        var connectedAt: Long? = null,
        var active: Boolean = false,
    ) {
        val isVoip: Boolean get() = source == Source.VOIP_NOTIFICATION ||
            (source == Source.TELECOM && direction == CallDirection.VOIP)
    }

    private val sessions = linkedMapOf<String, Session>()
    private val handler = Handler(Looper.getMainLooper())
    private val suppressed = mutableSetOf<String>()
    private var recordingId: String? = null
    private var sawRinging = false
    private var pendingIncomingNumber: String? = null

    @Volatile var telecomBound = false
        private set

    @Synchronized
    fun onTelecomBound(bound: Boolean) {
        telecomBound = bound
        Log.i("InCallService bound=$bound")
    }

    @Synchronized
    fun onTelecomEvent(
        context: Context,
        id: String,
        state: Int,
        packageName: String?,
        voip: Boolean,
        flow: CallDirection,
        number: String?,
        name: String?,
        accountId: String?,
        connectedAt: Long?,
    ) {
        if (!Prefs.experimentalCallDetection(context)) return
        val session = sessions.getOrPut(id) { Session(id, Source.TELECOM) }
        session.packageName = packageName
        session.direction = if (voip) CallDirection.VOIP else flow
        session.flowDirection = flow
        session.accountId = accountId ?: session.accountId
        if (!number.isNullOrBlank()) {
            session.number = number
            session.numberSource = "telecom"
        }
        if (!name.isNullOrBlank()) {
            session.name = name
            session.nameSource = "telecom"
        }
        val wasActive = session.active
        session.active = state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING
        if (session.active && !wasActive) {
            session.connectedAt = connectedAt ?: System.currentTimeMillis()
        }
        if (state == Call.STATE_DISCONNECTED) {
            remove(context, id)
            return
        }
        // Adopt an existing legacy SIM/notification recording instead of recording twice.
        val legacy = if (voip)
            sessions.values.firstOrNull { it.source == Source.VOIP_NOTIFICATION && it.packageName == packageName }
        else sessions["sim-legacy"]
        if (legacy != null) {
            if (session.number == null) { session.number = legacy.number; session.numberSource = legacy.numberSource }
            if (session.name == null) { session.name = legacy.name; session.nameSource = legacy.nameSource }
            if (recordingId == legacy.id) recordingId = id
            if (suppressed.remove(legacy.id)) suppressed.add(id)
            sessions.remove(legacy.id)
        }
        Log.i("Telecom call state=$state voip=$voip app=" + (packageName ?: "unknown") +
            " namePresent=" + (session.name != null) + " numberPresent=" + (session.number != null))
        reconcile(context)
    }

    @Synchronized
    fun onTelecomRemoved(context: Context, id: String) = remove(context, id)

    @Synchronized
    fun onSimState(context: Context, state: String?, number: String?) {
        when (state) {
            "RINGING" -> {
                sawRinging = true
                if (!number.isNullOrBlank()) pendingIncomingNumber = number
            }
            "OFFHOOK" -> {
                if (sessions.values.any { it.source == Source.TELECOM && !it.isVoip }) return
                val session = sessions.getOrPut("sim-legacy") {
                    Session("sim-legacy", Source.SIM, direction =
                        if (sawRinging) CallDirection.INCOMING else CallDirection.OUTGOING)
                }
                if (!number.isNullOrBlank()) pendingIncomingNumber = number
                if (session.number == null) session.number = pendingIncomingNumber
                if (session.number != null) session.numberSource = "phone-state"
                if (session.name == null) session.name = CallInfo.describe(context, session.number)
                    ?.takeIf { it != session.number }
                session.active = true
                if (session.connectedAt == null) session.connectedAt = System.currentTimeMillis()
                reconcile(context)
            }
            "IDLE" -> {
                sawRinging = false
                pendingIncomingNumber = null
                remove(context, "sim-legacy")
            }
        }
    }

    /**
     * Called only for genuine call notifications from the source package. A notification
     * is never allowed to terminate or create an independent recording for a Telecom call.
     */
    @Synchronized
    fun onNotification(
        context: Context,
        packageName: String,
        isPhoneDialer: Boolean,
        status: String,
        metadata: CallerMetadata?,
    ) {
        val candidates = sessions.values.filter { it.source == Source.TELECOM &&
            (it.packageName == packageName || (isPhoneDialer && !it.isVoip)) }
        val target = if (candidates.size == 1) candidates.single()
            else if (isPhoneDialer) sessions["sim-legacy"] else null

        if (target != null) {
            applyMetadata(target, metadata)
            if (target.id == recordingId) updateRecording(context, target)
            return
        }
        if (isPhoneDialer || !Prefs.recordVoip(context)) return

        val id = "notif:" + packageName
        if (status == "IDLE") {
            remove(context, id)
            return
        }
        val session = sessions.getOrPut(id) {
            Session(id, Source.VOIP_NOTIFICATION, packageName = packageName,
                direction = CallDirection.VOIP)
        }
        applyMetadata(session, metadata)
        if (status == "ACTIVE") {
            session.active = true
            session.connectedAt = session.connectedAt ?: System.currentTimeMillis()
        }
        reconcile(context)
    }

    @Synchronized
    fun needsScreenMetadata(packageName: String): Boolean =
        sessions.values.count { it.active && it.packageName == packageName } == 1 &&
        sessions.values.any { it.active && it.packageName == packageName &&
            (it.name == null || it.number == null) }

    @Synchronized
    fun onAccessibilityMetadata(context: Context, packageName: String, metadata: CallerMetadata) {
        val candidates = sessions.values.filter { it.active && it.packageName == packageName }
        if (candidates.size != 1) return // Never attach a caller to the wrong parallel call.
        val session = candidates.single()
        applyMetadata(session, metadata)
        if (recordingId == session.id) updateRecording(context, session)
    }

    private fun applyMetadata(session: Session, candidate: CallerMetadata?) {
        if (candidate == null) return
        // Never replace a direct Telecom identity with a weaker inferred value.
        if (session.name == null && candidate.name != null) {
            session.name = candidate.name
            session.nameSource = candidate.source
        }
        if (session.number == null && candidate.number != null) {
            session.number = candidate.number
            session.numberSource = if (candidate.inferredNumber) "contacts-from-" + candidate.source
                else candidate.source
        }
    }

    private fun remove(context: Context, id: String) {
        sessions.remove(id)
        suppressed.remove(id)
        if (recordingId == id) {
            recordingId = null
            RecordingService.stop(context.applicationContext, System.currentTimeMillis())
            // RecordingEngine finalizes asynchronously. Only start a waiting second call
            // after the previous foreground recording service has returned to Idle.
            scheduleWaitingCall(context.applicationContext, 0)
        } else {
            reconcile(context)
        }
    }

    private fun scheduleWaitingCall(context: Context, retry: Int) {
        if (retry > 30 || sessions.values.none { it.active }) return
        handler.postDelayed({
            synchronized(this) {
                if (recordingId != null || sessions.values.none { it.active }) return@synchronized
                if (RecordingService.state.value is RecordingService.State.Idle) {
                    reconcile(context)
                } else {
                    scheduleWaitingCall(context, retry + 1)
                }
            }
        }, 300L)
    }

    private fun reconcile(context: Context) {
        val current = recordingId?.let(sessions::get)
        if (current != null) {
            updateRecording(context, current)
            return
        }
        // Do not start a second recording until the current one is finished.
        if (recordingId != null || !Prefs.autoRecord(context)) return
        if (RecordingService.state.value !is RecordingService.State.Idle) return
        val selected = sessions.values.firstOrNull {
            it.active && it.id !in suppressed && (!it.isVoip || Prefs.recordVoip(context))
        } ?: return
        recordingId = selected.id
        Log.i("Start call recording via " + selected.source.name +
            " app=" + (selected.packageName ?: "phone") +
            " callerKnown=" + (selected.name != null || selected.number != null))
        RecordingService.start(context.applicationContext, selected.direction,
            selected.name ?: selected.number, selected.number, selected.connectedAt,
            selected.packageName, selected.accountId, selected.numberSource,
            selected.flowDirection.name)
    }

    private fun updateRecording(context: Context, session: Session) {
        RecordingService.updateMetadata(context.applicationContext,
            session.direction, session.name ?: session.number, session.number,
            session.packageName, session.accountId, session.numberSource,
            session.flowDirection.name)
    }

    /** The manual button never creates a second recording over an automatic call. */
    @Synchronized
    fun manualStart(context: Context) {
        if (recordingId != null) return
        val session = Session("manual", Source.MANUAL, active = true)
        sessions[session.id] = session
        recordingId = session.id
        RecordingService.start(context.applicationContext, CallDirection.UNKNOWN, null)
    }

    @Synchronized
    fun manualStop(context: Context) {
        val id = recordingId ?: return
        suppressed.add(id)
        if (id == "manual") sessions.remove(id)
        recordingId = null
        RecordingService.stop(context.applicationContext, System.currentTimeMillis())
    }

    val isRecording: Boolean @Synchronized get() = recordingId != null
}
