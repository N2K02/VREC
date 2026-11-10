package com.vrec.app.recording

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vrec.app.Prefs
import com.vrec.app.calls.CallMonitor
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Explicit user toggle only: never silently bootstraps ADB, pairs, or opens a microphone.
 * A failed mode acknowledgement restores the previous preference.
 */
class RecordingQuickTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toggleMutex = Mutex()

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        // Serialize quick repeated taps; always reread the latest persisted setting.
        scope.launch {
          toggleMutex.withLock {
            val old = Prefs.recordingEnabled(applicationContext)
            // Do not change automatic capture mode in the middle of a call.
            // Shell recording ownership remains authoritative even if Telecom
            // has not yet delivered the call session to the app process.
            if (CallMonitor.hasLiveCall() ||
                AdbFallbackNotice.enhancedActive.value ||
                AdbFallbackNotice.enhancedPaused.value) {
                com.vrec.app.Log.i("Quick tile: recording mode locked during an active call")
                update()
                return@withLock
            }
            val target = !old
            val available = !target || ExperimentalCaptureDaemon.probe(applicationContext)
            if (available) Prefs.setRecordingEnabled(applicationContext, target)
            val ok = available && runCatching {
                ExperimentalCaptureDaemon.setMode(applicationContext, target)
            }.getOrDefault(false)
            if (!ok) {
                if (available) {
                    Prefs.setRecordingEnabled(applicationContext, old)
                    runCatching { ExperimentalCaptureDaemon.setMode(applicationContext, old) }
                }
                com.vrec.app.Log.w("Quick tile: recorder unavailable or mode change not acknowledged")
            }
            update()
          }
        }
    }

    private fun update() {
        qsTile?.let { tile ->
            tile.state = if (Prefs.recordingEnabled(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "VRec recording"
            tile.updateTile()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
