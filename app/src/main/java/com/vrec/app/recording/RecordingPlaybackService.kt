package com.vrec.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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

        fun send(context: Context, action: String): Boolean {
            val intent = Intent(context, RecordingPlaybackService::class.java).setAction(action)
            return runCatching {
                if (action == ACTION_STOP) context.startService(intent)
                else context.startForegroundService(intent)
            }.onFailure {
                com.vrec.app.Log.w("Unable to open playback controls", it)
            }.isSuccess
        }
    }

    private var session: MediaSession? = null
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording playback", NotificationManager.IMPORTANCE_LOW)
        )
        player.setOnCompletionListener { stopPlayback() }
        player.setOnErrorListener { _, _, _ -> stopPlayback(); true }
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
        if (currentPath == null && intent?.action != ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> { stopPlayback(); return START_NOT_STICKY }
            ACTION_PAUSE -> pause()
            ACTION_PLAY -> resume()
        }
        if (!paused && audioManager.requestAudioFocus(focusRequest) !=
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED) pause()
        val notice = notification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ID, notice)
        return START_NOT_STICKY
    }

    private fun pause() {
        runCatching { if (player.isPlaying) player.pause() }
        paused = true
        audioManager.abandonAudioFocusRequest(focusRequest)
        refresh()
    }

    private fun resume() {
        if (currentPath == null) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            return
        paused = !runCatching { player.start() }.isSuccess
        refresh()
    }

    private fun stopPlayback() {
        runCatching { player.reset() }
        audioManager.abandonAudioFocusRequest(focusRequest)
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
            .setContentText("Saved recording")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
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
        // If Android tears down the service, never leave audio running without
        // its foreground controls or an active media session.
        runCatching { player.reset() }
        currentPath = null
        paused = false
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        session?.release()
        session = null
        super.onDestroy()
    }
}
