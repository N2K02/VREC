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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        data class Recording(val sourceLabel: String, val peer: String?, val silentSoFar: Boolean) : State
    }

    companion object {
        private const val CHANNEL = "vrec_recording"
        private const val NOTIF_ID = 42

        const val ACTION_START = "com.vrec.app.START"
        const val ACTION_STOP = "com.vrec.app.STOP"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_PEER = "peer"

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state

        /** 0..32767 running peak, for a live level meter. */
        private val _level = MutableStateFlow(0)
        val level: StateFlow<Int> = _level

        fun start(context: Context, direction: CallDirection, peer: String?) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DIRECTION, direction.name)
                .putExtra(EXTRA_PEER, peer)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                Log.w("Android blocked starting the recording service from the background", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Log.w("Couldn't deliver stop to the recording service", e)
            }
        }
    }

    private var engine: RecordingEngine? = null
    @Volatile private var direction = CallDirection.UNKNOWN
    @Volatile private var peer: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sampler = object : Runnable {
        override fun run() {
            engine?.let {
                it.sampleAmplitude()
                _level.value = it.maxAmplitudeSeen
                (_state.value as? State.Recording)?.let { s ->
                    if (!s.silentSoFar && it.maxAmplitudeSeen >= 120) {
                        _state.value = s.copy(silentSoFar = false)
                    }
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_START -> begin(intent)
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (engine != null) return // already recording
        direction = runCatching { CallDirection.valueOf(intent.getStringExtra(EXTRA_DIRECTION) ?: "") }
            .getOrDefault(CallDirection.UNKNOWN)
        peer = intent.getStringExtra(EXTRA_PEER)
        if (!goForeground(startingNotification())) {
            // Android refused the microphone foreground service (often a background-start block).
            // Abort cleanly rather than risk the "didn't call startForeground" crash.
            _state.value = State.Idle
            stopSelf()
            return
        }
        _state.value = State.Starting

        val e = RecordingEngine(this)
        engine = e
        val file = RecordingStore.newFile(this)
        thread(name = "vrec-record") {
            val source = e.start(file)
            if (source == null) {
                engine = null
                _state.value = State.Idle
                finish()
                return@thread
            }
            _state.value = State.Recording(source.label, peer, silentSoFar = true)
            handler.post { update(recordingNotification(source.label)); handler.post(sampler) }
        }
    }

    private fun finish() {
        handler.removeCallbacks(sampler)
        val e = engine
        engine = null
        if (e == null) {
            stopSelfAndForeground()
            return
        }
        thread(name = "vrec-stop") {
            val result = e.stop()
            if (result != null) {
                val keep = !(result.silent && Prefs.discardSilent(this))
                if (keep) {
                    RecordingStore.writeMeta(result.file, direction, peer, result.source.label, result.silent, result.durationMs)
                    Log.i("Saved recording (${result.durationMs / 1000}s, ${result.source.label}${if (result.silent) ", silent" else ""})")
                } else {
                    RecordingStore.delete(RecordingToDelete(result.file))
                    Log.i("Discarded a silent recording (setting on)")
                }
            }
            _state.value = State.Idle
            _level.value = 0
            stopSelfAndForeground()
        }
    }

    private fun stopSelfAndForeground() {
        handler.post {
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
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private fun startingNotification(): Notification = baseNotification()
        .setContentTitle("Preparing to record…")
        .setContentText(peer ?: "Call")
        .build()

    private fun recordingNotification(sourceLabel: String): Notification = baseNotification()
        .setContentTitle("Recording call")
        .setContentText((peer?.let { "$it · " } ?: "") + sourceLabel)
        .addAction(0, "Stop", stopPendingIntent())
        .build()

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
