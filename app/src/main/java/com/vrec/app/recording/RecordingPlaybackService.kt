package com.vrec.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.vrec.app.MainActivity
import com.vrec.app.R

/** Playback belongs to a media foreground service, not to a Compose screen. */
class RecordingPlaybackService : Service() {
    companion object {
        const val CHANNEL = "vrec_playback"
        const val ACTION_PLAY = "com.vrec.app.playback.PLAY"
        const val ACTION_PAUSE = "com.vrec.app.playback.PAUSE"
        const val ACTION_STOP = "com.vrec.app.playback.STOP"
        const val ACTION_REFRESH = "com.vrec.app.playback.REFRESH"
        private const val ID = 876
        val player: MediaPlayer by lazy { MediaPlayer() }
        @Volatile var currentPath: String? = null
        @Volatile var paused = false

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingPlaybackService::class.java).setAction(action)
            if (action == ACTION_STOP) context.startService(intent)
            else context.startForegroundService(intent)
        }
    }

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording playback", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "VRec playback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stopPlayback() }
                override fun onSeekTo(pos: Long) {
                    runCatching { player.seekTo(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) }
                }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
            ACTION_PAUSE -> pause()
            ACTION_PLAY -> resume()
        }
        val notice = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ID, notice)
        return START_NOT_STICKY
    }

    private fun pause() {
        runCatching { if (player.isPlaying) player.pause() }
        paused = true
        refresh()
    }

    private fun resume() {
        if (currentPath != null) runCatching { player.start() }
        paused = false
        refresh()
    }

    private fun stopPlayback() {
        runCatching { player.reset() }
        currentPath = null
        paused = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refresh() {
        if (currentPath == null) return
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }

    private fun control(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code,
            Intent(this, RecordingPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val state = if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        session?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(state, runCatching { player.currentPosition.toLong() }.getOrDefault(0L), 1f)
            .build())
        val home = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("VRec recording playback")
            .setContentText(currentPath?.substringAfterLast('/') ?: "Saved recording")
            .setContentIntent(home)
            .setOngoing(!paused)
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken))
            .addAction(Notification.Action.Builder(
                R.drawable.ic_stop, if (paused) "Play" else "Pause",
                control(if (paused) ACTION_PLAY else ACTION_PAUSE, 1)).build())
            .addAction(Notification.Action.Builder(R.drawable.ic_stop, "Stop",
                control(ACTION_STOP, 2)).build())
            .build()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
