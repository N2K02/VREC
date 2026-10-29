package com.vrec.app.recording

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.vrec.app.Prefs
import com.vrec.app.Log
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Compatibility facade for the ADB-only recording architecture.
 * Audio capture, pause, resume, and finalization belong exclusively to the
 * independent authenticated shell helper. No microphone engine is constructed.
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
        const val ACTION_START = "com.vrec.app.START"
        const val ACTION_STOP = "com.vrec.app.STOP"
        const val ACTION_USER_STOP = "com.vrec.app.USER_STOP"
        const val ACTION_PAUSE = "com.vrec.app.PAUSE"
        const val ACTION_RESUME = "com.vrec.app.RESUME"
        const val ACTION_UPDATE_PEER = "com.vrec.app.UPDATE_PEER"
        const val ACTION_UPDATE_METADATA = "com.vrec.app.UPDATE_METADATA"
        const val ACTION_ADB_HANDOFF = "com.vrec.app.ADB_HANDOFF"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_PEER = "peer"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_CALL_CONNECTED_AT = "call_connected_at"
        const val EXTRA_CALL_ENDED_AT = "call_ended_at"
        const val EXTRA_APP_PACKAGE = "app_package"
        const val EXTRA_PHONE_ACCOUNT = "phone_account"
        const val EXTRA_NUMBER_SOURCE = "number_source"
        const val EXTRA_CALL_FLOW = "call_flow"

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state
        private val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        init {
            // Shell STARTED/PAUSED/ENDED events own capture status.
            observer.launch {
                combine(AdbFallbackNotice.enhancedActive,
                    AdbFallbackNotice.enhancedPaused) { active, paused ->
                    when {
                        !active -> State.Idle
                        paused -> State.Paused("ADB shell", null, false, 0L)
                        else -> State.Recording("ADB shell", null, false, 0L)
                    }
                }.collect { _state.value = it }
            }
        }
        private val _level = MutableStateFlow(0)
        val level: StateFlow<Int> = _level

        fun start(context: Context, direction: CallDirection, peer: String?,
                  phoneNumber: String? = null, callConnectedAt: Long? = null,
                  appPackage: String? = null, accountId: String? = null,
                  numberSource: String? = null, callFlow: String? = null) {
            if (!Prefs.recordingEnabled(context)) return
            // The shell helper observes calls independently. Never start a
            // microphone recorder or a duplicate recording from this facade.
            if (!ExperimentalCaptureDaemon.ready.value) {
                Log.w("ADB shell capture unavailable; recording not started")
                RecordingFailureNotice.warn(context, "Call detected, but the ADB recording helper is unavailable. No audio was recorded. Open VRec to restore ADB.")
            }
        }

        fun stop(context: Context, callEndedAt: Long? = null) {
            // Shell call tracking owns automatic stop and file finalization.
            // Never stop an unrelated shell capture because of a stale call event.
        }

        fun updateMetadata(context: Context, direction: CallDirection, peer: String?,
                           number: String?, appPackage: String?, accountId: String?,
                           numberSource: String?, callFlow: String?) {
            // Metadata is collected by the shell call metadata journal.
        }

        fun updateVoipPeer(context: Context, newPeer: String) {
            // VoIP identity is collected by the shell call metadata journal.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Legacy service intents cannot create an ordinary Android microphone
        // capture. The authenticated shell control protocol owns all actions.
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
