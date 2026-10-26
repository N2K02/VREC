package com.vrec.app.experimental.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vrec.app.R
import com.vrec.app.recording.AdbFallbackNotice
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Shell-only, authenticated wakeup to copy finished calls into VRec storage. */
class EnhancedRecordingEventService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ExperimentalCaptureDaemon.isAuthorizedWake(
                this, intent?.getStringExtra("token"))) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val channelId = "vrec_enhanced_import"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channelId, "Enhanced call imports",
                NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("VRec enhanced recording")
            .setContentText("Saving completed call recording")
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, 874, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else 0,
            )
        } catch (_: Exception) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                when (intent?.action) {
                    "com.vrec.app.CALL_STARTED" -> {
                        val file = intent?.getStringExtra("recfile").orEmpty()
                        if (file.isNotBlank()) {
                            AdbFallbackNotice.shellRecordingStarted(applicationContext, file)
                        }
                    }
                    "com.vrec.app.CALL_ENDED" -> {
                        AdbFallbackNotice.shellRecordingEnded(applicationContext,\n                            intent?.getStringExtra("recfile").orEmpty())
                        ExperimentalCaptureDaemon.collect(applicationContext)
                    }
                }
            } finally {
                ServiceCompat.stopForeground(this@EnhancedRecordingEventService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
