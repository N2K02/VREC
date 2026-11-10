package com.vrec.app.recording

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vrec.app.Prefs
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Explicit user toggle only: never silently bootstraps ADB, pairs, or opens a microphone.
 * A failed mode acknowledgement restores the previous preference.
 */
class RecordingQuickTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    override fun onClick() {
        super.onClick()
        val old = Prefs.recordingEnabled(this)
        val target = !old
        // Do not show "on" until an existing authenticated helper accepts it.
        scope.launch {
            val ok = if (target && !ExperimentalCaptureDaemon.probe(applicationContext)) false
                else ExperimentalCaptureDaemon.setMode(applicationContext, target)
            if (ok) Prefs.setRecordingEnabled(applicationContext, target)
            else com.vrec.app.Log.w("Quick tile: recorder unavailable or mode change not acknowledged")
            update()
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
