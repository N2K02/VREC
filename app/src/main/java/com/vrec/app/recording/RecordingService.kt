package com.vrec.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.R
import com.vrec.app.calls.CallMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlin.concurrent.thread

/**
 * Holds the microphone and writes the file while a call is active. Started and stopped by
 * [com.vrec.app.calls.CallMonitor] as calls come and go. Runs as a foreground service of type
 * microphone so Android 11+ lets it keep recording with the screen off.
 */
class RecordingService : Service() {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data object Stopping : State
        data class Recording(
            val sourceLabel: String,
            val peer: String?,
            val silentSoFar: Boolean,
            val elapsedMs: Long = 0L,
        ) : State
        data class Paused(
            val sourceLabel: String,
            val peer: String?,
            val silentSoFar: Boolean,
            val elapsedMs: Long,
        ) : State
    }

    companion object {
        private const val CHANNEL = "vrec_recording"
        private const val NOTIF_ID = 42

        const val ACTION_START = "com.vrec.app.START"
        const val ACTION_STOP = "com.vrec.app.STOP"
        const val ACTION_USER_STOP = "com.vrec.app.USER_STOP"
        const val ACTION_PAUSE = "com.vrec.app.PAUSE"
        const val ACTION_UPDATE_PEER = "com.vrec.app.UPDATE_PEER"
        const val ACTION_UPDATE_METADATA = "com.vrec.app.UPDATE_METADATA"
        const val ACTION_ADB_HANDOFF = "com.vrec.app.ADB_HANDOFF"
        const val EXTRA_APP_PACKAGE = "app_package"
        const val EXTRA_PHONE_ACCOUNT = "phone_account"
        const val EXTRA_NUMBER_SOURCE = "number_source"
        const val EXTRA_CALL_FLOW = "call_flow"
        const val ACTION_RESUME = "com.vrec.app.RESUME"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_PEER = "peer"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CALL_CONNECTED_AT = "call_connected_at"
        const val EXTRA_CALL_ENDED_AT = "call_ended_at"
        const val EXTRA_ADB_FALLBACK = "adb_fallback"

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state

        /** 0..32767 running peak, for a live level meter. */
        private val _level = MutableStateFlow(0)
        val level: StateFlow<Int> = _level

        fun start(context: Context, direction: CallDirection, peer: String?, phoneNumber: String? = null,
                  callConnectedAt: Long? = null, appPackage: String? = null, accountId: String? = null,
                  numberSource: String? = null, callFlow: String? = null,
                  forceStandardFallback: Boolean = false) {
            if (!Prefs.recordingEnabled(context)) {
                Log.i("Recording master switch is off; ignoring start")
                return
            }
            // One recording per call: the enhanced helper owns cellular calls
            // only while ready; otherwise reserve an immediate standard fallback.
            // Keep the user's selected ADB mode for the NEXT call.
            val adbSelected = ExperimentalRecording.method(context) ==
                ExperimentalRecording.Method.BUILT_IN_ADB
            val helperReady = ExperimentalCaptureDaemon.ready.value
            val shellOwnsCellular = direction == CallDirection.INCOMING ||
                direction == CallDirection.OUTGOING
            val shellOwnsVoip = direction == CallDirection.VOIP &&
                ExperimentalCaptureDaemon.voipArmed.value &&
                com.vrec.app.experimental.daemon.EnhancedVoipPolicy.isShellVoipCall(
                    enabled = Prefs.recordVoip(context),
                    adbSelected = adbSelected,
                    sdk = Build.VERSION.SDK_INT,
                    appPackage = appPackage,
                )
            Log.i("Capture diagnostic: request direction=$direction adb=$adbSelected helperReady=$helperReady voipArmed=${ExperimentalCaptureDaemon.voipArmed.value} shellCellular=$shellOwnsCellular shellVoip=$shellOwnsVoip")
            if (adbSelected && helperReady && !forceStandardFallback &&
                (shellOwnsCellular || shellOwnsVoip)) {
                Log.i("Capture diagnostic: delegated to shell helper; app recorder not started; audio/file verification pending")
                // The independent shell recorder owns this call. Do not start a
                // second microphone capture that could silence the call app.
                return
            }
            if (adbSelected) {
                // ADB-only mode: never silently fall back to capturing the microphone.
                // A missing or unsupported shell source must stay unavailable.
                Log.w("Capture diagnostic: unsupported or unavailable ADB source direction=$direction helperReady=$helperReady voipArmed=${ExperimentalCaptureDaemon.voipArmed.value}; no file started and no microphone fallback")
                return
            }
            val standardFallback = false
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_ADB_FALLBACK, standardFallback)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DIRECTION, direction.name)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_PHONE_NUMBER, phoneNumber)
                .putExtra(EXTRA_CALL_CONNECTED_AT, callConnectedAt ?: 0L)
                .putExtra(EXTRA_APP_PACKAGE, appPackage)
                .putExtra(EXTRA_PHONE_ACCOUNT, accountId)
                .putExtra(EXTRA_NUMBER_SOURCE, numberSource)
                .putExtra(EXTRA_CALL_FLOW, callFlow)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                if (standardFallback) AdbFallbackNotice.clear()
                Log.w("Android blocked starting the recording service from the background", e)
            }
        }

        /**
         * Called after the enhanced helper has been verified, not on mere ADB
         * pairing. The active standard segment remains untouched unless the
         * helper itself confirms it sees this cellular call.
         */
        fun requestAdbHandoff(context: Context) {
            if (!AdbFallbackNotice.active.value ||
                _state.value !is State.Recording ||
                !CallMonitor.canHandoffToAdb()) return
            runCatching {
                context.startService(
                    Intent(context, RecordingService::class.java).setAction(ACTION_ADB_HANDOFF))
            }.onFailure { Log.w("Could not request ADB recording handoff", it) }
        }

        fun updateMetadata(context: Context, direction: CallDirection, peer: String?,
                           number: String?, appPackage: String?, accountId: String?,
                           numberSource: String?, callFlow: String?) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_UPDATE_METADATA)
                .putExtra(EXTRA_DIRECTION, direction.name)
                .putExtra(EXTRA_PEER, peer)
                .putExtra(EXTRA_PHONE_NUMBER, number)
                .putExtra(EXTRA_APP_PACKAGE, appPackage)
                .putExtra(EXTRA_PHONE_ACCOUNT, accountId)
                .putExtra(EXTRA_NUMBER_SOURCE, numberSource)
                .putExtra(EXTRA_CALL_FLOW, callFlow)
            runCatching { context.startService(intent) }
        }

        fun updateVoipPeer(context: Context, newPeer: String) {
            runCatching { context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_UPDATE_PEER).putExtra(EXTRA_PEER, newPeer)) }
        }

        fun stop(context: Context, callEndedAt: Long? = null) {
            try {
                context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_CALL_ENDED_AT, callEndedAt ?: 0L))
            } catch (e: Exception) {
                Log.w("Couldn't deliver stop to the recording service", e)
            }
        }
    }

    private var engine: RecordingEngine? = null
    private var finalizing = false
    private var stopPending = false
    @Volatile private var direction = CallDirection.UNKNOWN
    @Volatile private var peer: String? = null
    @Volatile private var phoneNumber: String? = null
    private var callConnectedAt: Long? = null
    private var appPackage: String? = null
    private var accountId: String? = null
    private var numberSource: String? = null
    private var callFlow: String? = null
    private var callEndedAt: Long? = null
    private var adbFallback = false
    private var handoffRequested = false
    private var handoffCheckPending = false

    private fun finishAdbFallback() {
        if (!adbFallback) return
        adbFallback = false
        AdbFallbackNotice.clear()
        // When ADB is restored mid-call it stays in standby, avoiding duplicate
        // files. After standard finalization, rearm it only for future calls.
        if (Prefs.recordingEnabled(applicationContext) &&
            ExperimentalRecording.method(applicationContext) == ExperimentalRecording.Method.BUILT_IN_ADB &&
            ExperimentalCaptureDaemon.ready.value) {
            CoroutineScope(Dispatchers.IO).launch {
                ExperimentalCaptureDaemon.setMode(applicationContext, true)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            val e = engine
            val current = _state.value as? State.Recording
            if (e != null && current != null) {
                e.sampleAmplitude()
                _level.value = e.maxAmplitudeSeen
                // Flip to "not silent" the moment any real sound is captured.
                _state.value = current.copy(
                    silentSoFar = current.silentSoFar && e.maxAmplitudeSeen < 120,
                    elapsedMs = e.elapsedRecordingMs(),
                )
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                callEndedAt = intent.getLongExtra(EXTRA_CALL_ENDED_AT, 0L).takeIf { it > 0L }
                finish()
            }
            ACTION_USER_STOP -> CallMonitor.userStop(this)
            ACTION_ADB_HANDOFF -> requestHandoff()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_UPDATE_PEER -> {
                if (direction == CallDirection.VOIP && engine != null) {
                    val newPeer = intent.getStringExtra(EXTRA_PEER)
                    if (!newPeer.isNullOrBlank() && (peer.isNullOrBlank() || !newPeer.substringBefore(" (").equals(peer?.substringBefore(" ("), true))) {
                        peer = newPeer
                        when (val current = _state.value) {
                            is State.Recording -> { _state.value = current.copy(peer = newPeer); update(recordingNotification(current.sourceLabel)) }
                            is State.Paused -> { _state.value = current.copy(peer = newPeer); update(pausedNotification(current.sourceLabel)) }
                            else -> Unit
                        }
                    }
                }
            }
            ACTION_UPDATE_METADATA -> {
                if (engine != null) {
                    val incomingNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
                    if (!incomingNumber.isNullOrBlank() && phoneNumber.isNullOrBlank()) phoneNumber = incomingNumber
                    val incomingPeer = intent.getStringExtra(EXTRA_PEER)
                    if (!incomingPeer.isNullOrBlank() && peer != incomingPeer) peer = incomingPeer
                    val flow = intent.getStringExtra(EXTRA_CALL_FLOW)
                    if (!flow.isNullOrBlank()) callFlow = flow
                    val newerDirection = runCatching {
                        CallDirection.valueOf(intent.getStringExtra(EXTRA_DIRECTION) ?: "")
                    }.getOrNull()
                    if (newerDirection != null && newerDirection != CallDirection.UNKNOWN) direction = newerDirection
                    appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE) ?: appPackage
                    accountId = intent.getStringExtra(EXTRA_PHONE_ACCOUNT) ?: accountId
                    numberSource = intent.getStringExtra(EXTRA_NUMBER_SOURCE) ?: numberSource
                    when (val current = _state.value) {
                        is State.Recording -> {
                            _state.value = current.copy(peer = peer)
                            update(recordingNotification(current.sourceLabel))
                        }
                        is State.Paused -> {
                            _state.value = current.copy(peer = peer)
                            update(pausedNotification(current.sourceLabel))
                        }
                        else -> Unit
                    }
                }
            }
            ACTION_START -> begin(intent)
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        // Reject a new segment until the previous recording has fully saved.
        if (engine != null || finalizing || stopPending ||
            _state.value !is State.Idle) return
        // The preference may change between enqueueing ACTION_START and delivery.
        if (!Prefs.recordingEnabled(this)) {
            _state.value = State.Idle
            if (intent.getBooleanExtra(EXTRA_ADB_FALLBACK, false)) AdbFallbackNotice.clear()
            stopSelf()
            return
        }
        adbFallback = intent.getBooleanExtra(EXTRA_ADB_FALLBACK, false)
        direction = runCatching { CallDirection.valueOf(intent.getStringExtra(EXTRA_DIRECTION) ?: "") }
            .getOrDefault(CallDirection.UNKNOWN)
        peer = intent.getStringExtra(EXTRA_PEER)
        phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER)
        callConnectedAt = intent.getLongExtra(EXTRA_CALL_CONNECTED_AT, 0L).takeIf { it > 0L }
        appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE)
        accountId = intent.getStringExtra(EXTRA_PHONE_ACCOUNT)
        numberSource = intent.getStringExtra(EXTRA_NUMBER_SOURCE)
        callFlow = intent.getStringExtra(EXTRA_CALL_FLOW)
        callEndedAt = null
        if (!goForeground(startingNotification())) {
            // Android refused the microphone foreground service (often a background-start block).
            // Abort cleanly rather than risk the "didn't call startForeground" crash.
            _state.value = State.Idle
            finishAdbFallback()
            stopSelf()
            return
        }
        _state.value = State.Starting

        val e = RecordingEngine(this)
        engine = e
        val file = RecordingStore.newFile(this, direction, peer)
        thread(name = "vrec-record") {
            val source = runCatching { e.start(file) }.onFailure {
                Log.w("Recorder could not prepare an audio source", it)
            }.getOrNull()
            handler.post {
                if (stopPending) {
                    if (source == null) engine = null
                    finalizeRecording()
                    return@post
                }
                if (source == null) {
                    engine = null
                    Log.w("Capture diagnostic: standard audio engine failed to initialize; no recording started")
                    _state.value = State.Idle
                    finishAdbFallback()
                    CallMonitor.onRecordingFinalized(this)
                    stopSelfAndForeground()
                    return@post
                }
                Log.i("Capture diagnostic: standard audio engine initialized source=${source.label}; recording started, audio content not yet verified")
                _state.value = State.Recording(source.label, peer, silentSoFar = true)
                update(recordingNotification(source.label))
                if (adbFallback) {
                    Log.i("Enhanced ADB helper unavailable; recording this call via standard microphone")
                    AdbFallbackNotice.notifyStandardStarted(applicationContext)
                }
                if (Prefs.showOverlay(this) && !CallMonitor.overlayWasDismissed()) {
                    startService(Intent(this, OverlayControlService::class.java)
                        .setAction(OverlayControlService.ACTION_SHOW))
                }
                handler.post(sampler)
            }
        }
    }

    /**
     * Preflight without interrupting the microphone. Never hand off a VoIP,
     * unknown, paused, ending or already-disconnected call.
     */
    private fun requestHandoff() {
        if (handoffCheckPending || handoffRequested || finalizing || stopPending ||
            !adbFallback || _state.value !is State.Recording ||
            !Prefs.recordingEnabled(this) ||
            !ExperimentalCaptureDaemon.ready.value || !CallMonitor.canHandoffToAdb()) return
        handoffCheckPending = true
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val safeToSwitch = ExperimentalCaptureDaemon.canHandoffCurrentCellularCall(app)
            handler.post {
                handoffCheckPending = false
                if (!safeToSwitch || handoffRequested || finalizing || stopPending ||
                    !adbFallback || _state.value !is State.Recording ||
                    !Prefs.recordingEnabled(this@RecordingService) ||
                    ExperimentalRecording.method(this@RecordingService) !=
                        ExperimentalRecording.Method.BUILT_IN_ADB ||
                    !CallMonitor.canHandoffToAdb()) {
                    if (!safeToSwitch) Log.i(
                        "ADB helper has not verified the live cellular call; keeping standard audio")
                    return@post
                }
                AdbFallbackNotice.beginHandoff()
                handoffRequested = true
                Log.i("ADB helper verified current call; saving standard segment before handoff")
                finish()
            }
        }
    }

    /** Called only once the standard segment has actually finished saving. */
    private fun recordingFinished() {
        _state.value = State.Idle
        _level.value = 0
        finalizing = false
        if (!handoffRequested) {
            finishAdbFallback()
            CallMonitor.onRecordingFinalized(this)
            if (!CallMonitor.canRecordAgain() || !Prefs.recordingEnabled(this)) {
                stopService(Intent(this, OverlayControlService::class.java))
            }
            stopSelfAndForeground()
            return
        }

        handoffRequested = false
        adbFallback = false
        stopService(Intent(this, OverlayControlService::class.java))
        stopSelfAndForeground()
        val app = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            // Recheck the watcher after saving: the call might have ended
            // while MediaRecorder was finalizing. Never start on a stale call.
            val stillLive = CallMonitor.canHandoffToAdb() &&
                Prefs.recordingEnabled(app) &&
                ExperimentalCaptureDaemon.ready.value &&
                ExperimentalRecording.method(app) == ExperimentalRecording.Method.BUILT_IN_ADB &&
                ExperimentalCaptureDaemon.canHandoffCurrentCellularCall(app)
            val started = if (stillLive) {
                val armed = ExperimentalCaptureDaemon.setMode(
                    app, true, forVerifiedHandoff = true)
                if (armed) ExperimentalCaptureDaemon.startCurrentCellularCall(app)
                else Result.failure(IllegalStateException("Could not arm the enhanced recorder"))
            } else Result.failure(IllegalStateException("Active call was not verified after saving"))

            if (started.isSuccess && CallMonitor.canHandoffToAdb() &&
                Prefs.recordingEnabled(app)) {
                AdbFallbackNotice.clear()
                AdbFallbackNotice.endHandoff()
                CallMonitor.onAdbHandoffComplete(app)
                AdbFallbackNotice.notifyEnhancedStarted(app)
                Log.i("Enhanced recording handoff succeeded: " + started.getOrNull())
            } else {
                // If the shell command returned an error after starting capture,
                // its response may have been lost. Do not restart a microphone
                // recorder unless the shell confirms capture is OFF.
                val shellStopped = ExperimentalCaptureDaemon.setMode(app, false)
                AdbFallbackNotice.endHandoff()
                if (!shellStopped) {
                    Log.w("ADB handoff uncertain: could not confirm shell capture stopped")
                    ExperimentalCaptureDaemon.message.value =
                        "Handoff status unknown. Check enhanced capture before recording again."
                    AdbFallbackNotice.clear()
                    CallMonitor.onRecordingFinalized(app)
                } else if (CallMonitor.canHandoffToAdb() && Prefs.recordingEnabled(app)) {
                    Log.w("Enhanced start was not confirmed; recovering standard recording")
                    AdbFallbackNotice.reserve()
                    CallMonitor.recoverStandardAfterAdbHandoffFailure(app)
                } else {
                    AdbFallbackNotice.clear()
                    CallMonitor.onRecordingFinalized(app)
                }
            }
        }
    }

    private fun pauseRecording() {
        if (!Prefs.recordingEnabled(this) || finalizing || stopPending) return
        val e = engine ?: return
        val current = _state.value as? State.Recording ?: return
        if (!e.pause()) return
        handler.removeCallbacks(sampler)
        _level.value = 0
        _state.value = State.Paused(current.sourceLabel, current.peer, current.silentSoFar, e.elapsedRecordingMs())
        update(pausedNotification(current.sourceLabel))
    }

    private fun resumeRecording() {
        if (!Prefs.recordingEnabled(this) || finalizing || stopPending) return
        val e = engine ?: return
        val current = _state.value as? State.Paused ?: return
        if (!e.resume()) return
        _state.value = State.Recording(current.sourceLabel, current.peer, current.silentSoFar, current.elapsedMs)
        update(recordingNotification(current.sourceLabel))
        handler.post(sampler)
    }

    private fun finish() {
        if (finalizing || stopPending) return
        handler.removeCallbacks(sampler)
        if (_state.value is State.Starting) {
            // An audio source is being prepared on another thread. Waiting for
            // that thread avoids racing MediaRecorder.start() against stop().
            stopPending = true
            _state.value = State.Stopping
            update(stoppingNotification())
            return
        }
        finalizeRecording()
    }

    private fun finalizeRecording() {
        if (finalizing) return
        finalizing = true
        stopPending = false
        handler.removeCallbacks(sampler)
        val e = engine
        engine = null
        _state.value = State.Stopping
        update(stoppingNotification())
        if (e == null) {
            recordingFinished()
            return
        }
        thread(name = "vrec-stop") {
            try {
                val result = e.stop()
                if (result != null) {
                    val keep = !(result.silent && Prefs.discardSilent(this))
                    if (keep) {
                        // Attribute SIM details only when there is exactly one active
                        // subscription and this is a verified cellular call.
                        val sim = if (direction == CallDirection.INCOMING ||
                            direction == CallDirection.OUTGOING)
                            com.vrec.app.calls.CallInfo.singleActiveSim(this)
                        else null
                        RecordingStore.writeMeta(result.file, direction, peer,
                            result.source.label, result.silent, result.durationMs,
                            phoneNumber = phoneNumber,
                            contactName = peer?.substringBefore(" (")?.takeIf {
                                (direction == CallDirection.VOIP && it.isNotBlank() && it != peer) ||
                                    (phoneNumber != null && it != phoneNumber)
                            },
                            callConnectedAt = callConnectedAt, callEndedAt = callEndedAt,
                            appPackage = appPackage, accountId = accountId,
                            numberSource = numberSource, callFlowDirection = callFlow,
                            simLabel = sim?.label, simSlot = sim?.slot,
                            carrier = sim?.carrier)
                        Log.i("Saved recording (${result.durationMs / 1000}s, ${result.source.label}${if (result.silent) ", silent" else ""})")
                    } else {
                        RecordingStore.delete(RecordingToDelete(result.file))
                        Log.i("Discarded a silent recording (setting on)")
                    }
                }
            } catch (error: Exception) {
                Log.w("Recording finalization failed", error)
            } finally {
                handler.post { recordingFinished() }
            }
        }
    }

    private fun stopSelfAndForeground() {
        handler.post {
            // A user may press Record again as soon as saving completes.
            // Do not tear down a newly started recording during that transition.
            if (_state.value !is State.Idle || engine != null) return@post
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --------------------------------------------------------------- notifications

    private fun goForeground(notification: Notification): Boolean {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.w("Could not enter the foreground for recording (Android may block it from the background)", e)
            false
        }
    }

    private fun update(notification: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Call recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while VREC is recording a call."
                    setShowBadge(false)
                },
            )
        }
    }

    private fun baseNotification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setSound(null)
        .setVibrate(null)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openAppPendingIntent())
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private fun openAppPendingIntent() = android.app.PendingIntent.getActivity(
        this, 0,
        Intent(this, com.vrec.app.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    private fun startingNotification(): Notification = baseNotification()
        .setContentTitle("Preparing to record…")
        .setContentText(peer ?: "Call")
        .build()

    private fun stoppingNotification(): Notification = baseNotification()
        .setContentTitle("Saving recording…")
        .setContentText("Finishing the current audio file")
        .build()

    private fun recordingNotification(sourceLabel: String): Notification = baseNotification()
        .setContentTitle("Recording call")
        .setContentText((peer?.let { "$it · " } ?: "") + sourceLabel +
            (if (adbFallback) " · Standard fallback" else ""))
        .addAction(0, "Pause", actionPendingIntent(ACTION_PAUSE, 2))
        .addAction(0, "Stop", actionPendingIntent(ACTION_USER_STOP, 1))
        .build()

    private fun pausedNotification(sourceLabel: String): Notification = baseNotification()
        .setContentTitle("Recording paused")
        .setContentText(peer?.let { "$it · $sourceLabel" } ?: sourceLabel)
        .addAction(0, "Resume", actionPendingIntent(ACTION_RESUME, 3))
        .addAction(0, "Stop", actionPendingIntent(ACTION_USER_STOP, 1))
        .build()

    private fun actionPendingIntent(action: String, requestCode: Int) = android.app.PendingIntent.getService(
        this, requestCode,
        Intent(this, RecordingService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopPendingIntent() = android.app.PendingIntent.getService(
        this, 1,
        Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
        android.app.PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Tiny shim so [RecordingStore.delete] can take a bare file without full metadata. */
private fun RecordingToDelete(file: java.io.File) = Recording(
    file = file, startedAt = 0, direction = CallDirection.UNKNOWN,
    peer = null, sourceLabel = null, silent = true, durationMs = 0,
)
