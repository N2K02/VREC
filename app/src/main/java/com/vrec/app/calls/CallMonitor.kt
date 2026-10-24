package com.vrec.app.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.Call
import android.telecom.TelecomManager
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.RecordingService
import com.vrec.app.recording.OverlayControlService
import androidx.core.app.NotificationCompat
import com.vrec.app.R

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
    private val modeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val masterModeMutex = Mutex()
    // Automatic recording is attempted only once per active call session.
    // A user Stop must never trigger an automatic restart.
    private val autoAttempted = mutableSetOf<String>()
    private val suppressed = mutableSetOf<String>()
    private var recordingId: String? = null
    private var overlayDismissed = false
    private val recentlyEndedVoip = mutableMapOf<String, Long>()
    private const val RECENT_CALL_GUARD_MS = 12_000L
    private const val CONTROL_CHANNEL = "vrec_call_controls"
    private const val CONTROL_ID = 84
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
        if (!Prefs.experimentalCallDetection(context) || !Prefs.recordingEnabled(context)) return
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
        if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
            if (voip && packageName != null) {
                // Telecom may disconnect before WhatsApp retracts its call
                // notification. Do not re-detect that stale notification
                // as a brand-new recording session.
                recentlyEndedVoip[packageName] =
                    SystemClock.elapsedRealtime() + RECENT_CALL_GUARD_MS
            }
            Log.i("Telecom call ended app=" + (packageName ?: "phone") + " state=" + state)
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
            if (autoAttempted.remove(legacy.id)) autoAttempted.add(id)
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
        if (!Prefs.recordingEnabled(context)) {
            if (state == "IDLE") {
                sawRinging = false
                pendingIncomingNumber = null
            }
            return
        }
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
        if (!Prefs.recordingEnabled(context)) return
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
        if (status == "IDLE") recentlyEndedVoip.remove(packageName)
        else if (SystemClock.elapsedRealtime() <
            (recentlyEndedVoip[packageName] ?: 0L)) {
            // A late notification must not revive an already-ended Telecom call.
            return
        }
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
    fun needsScreenMetadata(packageName: String, phoneDialer: Boolean = false): Boolean {
        val candidates = sessions.values.filter {
            it.active && (it.packageName == packageName || (phoneDialer && !it.isVoip))
        }
        return candidates.size == 1 && (candidates.single().name == null || candidates.single().number == null)
    }

    @Synchronized
    fun onAccessibilityMetadata(context: Context, packageName: String,
                                metadata: CallerMetadata, phoneDialer: Boolean = false) {
        val candidates = sessions.values.filter {
            it.active && (it.packageName == packageName || (phoneDialer && !it.isVoip))
        }
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
        val removed = sessions.remove(id)
        suppressed.remove(id)
        autoAttempted.remove(id)
        if (removed != null) Log.i("Call ended; closing session " + id)
        if (recordingId == id) {
            recordingId = null
            RecordingService.stop(context.applicationContext, System.currentTimeMillis())
            // A distinct waiting call may start only after the previous file is saved.
            scheduleWaitingCall(context.applicationContext, 0)
        } else {
            reconcile(context)
        }
        if (!hasLiveCall()) {
            overlayDismissed = false
            dismissCallControls(context)
            dismissOverlay(context)
        } else if (RecordingService.state.value is RecordingService.State.Idle &&
            canRecordAgain()) {
            showCallControls(context)
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
        if (!Prefs.recordingEnabled(context)) return
        val current = recordingId?.let(sessions::get)
        if (current != null) {
            updateRecording(context, current)
            return
        }
        // Do not start a second recording until the current one is finished.
        if (recordingId != null || !Prefs.autoRecord(context)) return
        if (RecordingService.state.value !is RecordingService.State.Idle) return
        val selected = sessions.values.firstOrNull {
            it.active && it.id !in suppressed && it.id !in autoAttempted &&
                (!it.isVoip || Prefs.recordVoip(context))
        } ?: return
        autoAttempted.add(selected.id)
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

    /** A user Stop suppresses automatic restart for the remainder of the live call. */
    @Synchronized
    fun userStop(context: Context) {
        val state = RecordingService.state.value
        if (state is RecordingService.State.Idle ||
            state is RecordingService.State.Stopping) return
        val id = recordingId
        if (id != null) {
            suppressed.add(id)
            recordingId = null
            if (id == "manual") sessions.remove(id)
        } else {
            Log.w("User stopped a recorder with no tracked call session")
        }
        Log.i("User stopped recording; an explicit Record is required to restart")
        RecordingService.stop(context.applicationContext)
    }

    /**
     * Record again creates a new file for the same still-active call.
     * The suppression flag stays set; call notifications cannot auto-restart.
     */
    @Synchronized
    fun userRecordAgain(context: Context, expectedSession: String? = null) {
        if (!Prefs.recordingEnabled(context) || recordingId != null ||
            RecordingService.state.value !is RecordingService.State.Idle) return
        val chosen = sessions.values.firstOrNull {
            it.active && it.id in suppressed && it.source != Source.MANUAL &&
                (expectedSession == null || it.id == expectedSession)
        } ?: return
        recordingId = chosen.id
        dismissCallControls(context)
        Log.i("User started another recording segment for " + chosen.source.name)
        RecordingService.start(context.applicationContext, chosen.direction,
            chosen.name ?: chosen.number, chosen.number, chosen.connectedAt,
            chosen.packageName, chosen.accountId, chosen.numberSource,
            chosen.flowDirection.name)
    }

    @Synchronized
    fun manualStart(context: Context) {
        if (!Prefs.recordingEnabled(context) || recordingId != null ||
            RecordingService.state.value !is RecordingService.State.Idle) return
        if (canRecordAgain()) {
            userRecordAgain(context)
            return
        }
        val currentCall = sessions.values.firstOrNull {
            it.active && it.source != Source.MANUAL
        }
        if (currentCall != null) {
            // Explicit user-initiated recording is still linked to the call;
            // Android's end signal will therefore stop and save this segment.
            suppressed.add(currentCall.id)
            autoAttempted.add(currentCall.id)
            recordingId = currentCall.id
            RecordingService.start(context.applicationContext, currentCall.direction,
                currentCall.name ?: currentCall.number, currentCall.number,
                currentCall.connectedAt, currentCall.packageName, currentCall.accountId,
                currentCall.numberSource, currentCall.flowDirection.name)
        } else {
            val session = Session("manual", Source.MANUAL, active = true)
            sessions[session.id] = session
            recordingId = session.id
            RecordingService.start(context.applicationContext, CallDirection.UNKNOWN, null)
        }
    }

    @Synchronized
    fun manualStop(context: Context) = userStop(context)

    @Synchronized
    fun hasLiveCall(): Boolean = sessions.values.any {
        it.active && it.source != Source.MANUAL
    }

    /**
     * A handoff is allowed only for an actively tracked cellular call. The
     * shell daemon has its own independent watcher and must verify it too.
     */
    @Synchronized
    fun canHandoffToAdb(): Boolean {
        val current = recordingId?.let(sessions::get) ?: return false
        return current.active && !current.isVoip &&
            (current.direction == CallDirection.INCOMING ||
                current.direction == CallDirection.OUTGOING)
    }

    /** The saved standard segment gave way to a verified shell segment. */
    @Synchronized
    fun onAdbHandoffComplete(context: Context) {
        val id = recordingId
        recordingId = null
        if (id != null) autoAttempted.add(id)
        dismissCallControls(context)
        dismissOverlay(context)
        Log.i("Switched active cellular call from standard to enhanced capture")
    }

    /**
     * Enhanced mid-call start failed after the standard file was finalized.
     * Re-enter standard fallback only if that very call is still active.
     * This is a new file, never a resumed MediaRecorder instance.
     */
    @Synchronized
    fun recoverStandardAfterAdbHandoffFailure(context: Context) {
        val activeCall = recordingId?.let(sessions::get)
        if (!Prefs.recordingEnabled(context) ||
            activeCall == null || !activeCall.active || activeCall.isVoip ||
            RecordingService.state.value !is RecordingService.State.Idle) {
            onRecordingFinalized(context)
            return
        }
        Log.w("Enhanced handoff failed; starting a new standard call segment")
        RecordingService.start(
            context.applicationContext, activeCall.direction,
            activeCall.name ?: activeCall.number, activeCall.number,
            activeCall.connectedAt, activeCall.packageName, activeCall.accountId,
            activeCall.numberSource, activeCall.flowDirection.name,
            forceStandardFallback = true,
        )
    }

    @Synchronized
    fun canRecordAgain(): Boolean = recordingId == null && sessions.values.any {
        it.active && it.source != Source.MANUAL && it.id in suppressed
    }

    @Synchronized
    fun activeVoipPackages(): Set<String> = sessions.values
        // Telecom already owns its own lifecycle. Poll only independent
        // notification-based sessions, not all WhatsApp Telecom sessions.
        .filter { it.active && it.source == Source.VOIP_NOTIFICATION &&
            it.packageName != null }
        .mapNotNull { it.packageName }.toSet()

    @Synchronized
    fun overlayWasDismissed(): Boolean = overlayDismissed

    @Synchronized
    fun onOverlayDismissed() { overlayDismissed = true }

    /** Finalization is complete; the user can start a new segment, not automatic capture. */
    @Synchronized
    fun onRecordingFinalized(context: Context) {
        val orphan = recordingId
        if (orphan != null && RecordingService.state.value is RecordingService.State.Idle) {
            recordingId = null
            suppressed.add(orphan)
        }
        if (hasLiveCall() && canRecordAgain() && Prefs.recordingEnabled(context)) {
            showCallControls(context)
        } else {
            dismissCallControls(context)
            if (!hasLiveCall()) dismissOverlay(context)
        }
    }

    private fun showCallControls(context: Context) {
        val session = sessions.values.firstOrNull {
            it.active && it.id in suppressed && it.source != Source.MANUAL
        } ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CONTROL_CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CONTROL_CHANNEL, "Call recording controls", NotificationManager.IMPORTANCE_LOW))
        }
        val intent = Intent(context, CallControlReceiver::class.java)
            .setAction(CallControlReceiver.ACTION_RECORD_AGAIN)
            .putExtra(CallControlReceiver.EXTRA_SESSION_ID, session.id)
        val action = PendingIntent.getBroadcast(context, 84, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CONTROL_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording stopped")
            .setContentText("Call still active · Tap Record to start another file")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Record", action)
            .build()
        runCatching { manager.notify(CONTROL_ID, notification) }
            .onFailure { Log.w("Could not show Record-again notification", it) }
    }

    private fun dismissCallControls(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CONTROL_ID)
    }

    private fun dismissOverlay(context: Context) {
        // Stopping an existing overlay service is allowed while VRec is in the
        // background; sending ACTION_DISMISS with startService() can be rejected
        // by Android background-service restrictions.
        runCatching {
            context.stopService(Intent(context, OverlayControlService::class.java))
        }.onFailure { Log.w("Could not dismiss recording overlay", it) }
    }

    /**
     * Master switch used by both standard recording and the independent shell daemon.
     * Clear active call sessions when switched off so reenabling cannot resume an old call.
     */
    @Synchronized
    fun setRecordingEnabled(context: Context, enabled: Boolean) {
        Prefs.setRecordingEnabled(context, enabled)
        if (!enabled) {
            recordingId = null
            sessions.clear()
            suppressed.clear()
            autoAttempted.clear()
            recentlyEndedVoip.clear()
            overlayDismissed = false
            dismissCallControls(context)
            dismissOverlay(context)
            sawRinging = false
            pendingIncomingNumber = null
            if (RecordingService.state.value !is RecordingService.State.Idle) {
                RecordingService.stop(context.applicationContext, System.currentTimeMillis())
            }
        }
        val appContext = context.applicationContext
        modeScope.launch {
            // Ordering is essential when the user quickly switches Off / On.
            // Read the CURRENT preference inside the serialized section; an
            // older Off coroutine cannot disable a newer On choice afterwards.
            masterModeMutex.withLock {
                var applied = false
                repeat(3) { attempt ->
                    if (!applied) {
                        applied = ExperimentalCaptureDaemon.setMode(appContext,
                            Prefs.recordingEnabled(appContext))
                        if (!applied && attempt < 2) delay(400L)
                    }
                }
                if (!applied && ExperimentalCaptureDaemon.ready.value) {
                    ExperimentalCaptureDaemon.message.value =
                        "Recording switch changed, but the enhanced shell recorder did not " +
                        "confirm the new state. Reconnect ADB and verify before sensitive calls."
                    Log.w("Enhanced recording master switch could not be verified")
                }
            }
        }
        Log.i("Recording function " + if (enabled) "enabled" else "disabled")
    }

    val isRecording: Boolean @Synchronized get() = recordingId != null
}
