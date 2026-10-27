package com.vrec.app.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.MainActivity
import com.vrec.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shell recording lifecycle and notification bridge. Capture is owned by the
 * authenticated ADB helper, never by an Android microphone fallback.
 */
object AdbFallbackNotice {
    private const val CHANNEL = "vrec_adb_fallback"
    private const val NOTIFICATION_ID = 86
    val active = MutableStateFlow(false)
    val handoffInProgress = MutableStateFlow(false)
    val enhancedActive = MutableStateFlow(false)
    val enhancedPaused = MutableStateFlow(false)
    private val shellLifecycle = ShellRecordingLifecycle()

    /** Reserve before starting the standard recorder to prevent concurrent daemon arming. */
    fun reserve() {
        active.value = true
    }

    /** Clear when the standard recording ends; keep the dismissible setup reminder. */
    fun clear() {
        active.value = false
    }

    fun beginHandoff() { handoffInProgress.value = true }
    fun endHandoff() { handoffInProgress.value = false }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** Only a shell-authenticated STARTED event with a real recording file may show capture UI. */
    fun shellRecordingStarted(context: Context, file: String, reconciled: Boolean = false) {
        if (!(if (reconciled) shellLifecycle.reconcileRecording(file)
                else shellLifecycle.started(file))) return
        enhancedActive.value = true
        enhancedPaused.value = false
        if (Prefs.showOverlay(context) && !com.vrec.app.calls.CallMonitor.overlayWasDismissed()) {
            runCatching {
                context.startService(Intent(context, OverlayControlService::class.java)
                    .setAction(OverlayControlService.ACTION_SHOW))
            }.onFailure { Log.w("Shell overlay unavailable", it) }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "ADB recording status", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("VRec · Shell recording")
            .setContentText("Enhanced call audio capture started")
            .setContentIntent(PendingIntent.getActivity(context, 89,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .addAction(0, "Pause", controlIntent(context, 'P', 901))
            .addAction(0, "Stop", controlIntent(context, 'S', 902))
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w("Shell recording notification unavailable", it) }
    }

    fun shellRecordingEnded(context: Context, file: String) {
        if (!shellLifecycle.ended(file)) return
        enhancedActive.value = false
        enhancedPaused.value = false
        dismiss(context)
        runCatching { context.stopService(Intent(context, OverlayControlService::class.java)) }
    }

    private fun controlIntent(context: Context, action: Char, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(context, requestCode,
            Intent(context, EnhancedCaptureControlReceiver::class.java)
                .setAction("com.vrec.app.SHELL_CONTROL")
                .putExtra("control", action.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun setEnhancedPaused(context: Context, paused: Boolean) {
        if (paused) {
            if (!shellLifecycle.pausedByUser() && !enhancedPaused.value) return
            enhancedActive.value = true
        } else if (!shellLifecycle.isActive()) {
            // Resume can return before its STARTED wake. Do not claim a new
            // recording is active until that wake identifies the actual file.
            return
        }
        enhancedPaused.value = paused
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "ADB recording status", NotificationManager.IMPORTANCE_LOW))
        }
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (paused) "VRec · Recording paused" else "VRec · Shell recording")
            .setContentText(if (paused) "Resume or stop enhanced capture" else "Enhanced call audio capture active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (paused) "Resume" else "Pause",
                controlIntent(context, if (paused) 'R' else 'P', 901))
            .addAction(0, "Stop", controlIntent(context, 'S', 902))
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            manager.notify(NOTIFICATION_ID, notice)
    }

    /** Rebuild Android's transient UI after a process restart. */
    fun reconcileAuthenticatedRecording(context: Context, file: String) {
        // A duplicate STARTED is harmless; it must not recreate the overlay.
        shellRecordingStarted(context, file, reconciled = true)
        if (shellLifecycle.isActive() && enhancedPaused.value) {
            setEnhancedPaused(context, false)
        }
    }

    /** Paused captures have no active file, but still expose Resume and Stop. */
    fun reconcileAuthenticatedPaused(context: Context) {
        shellLifecycle.reconcilePaused()
        if (!enhancedActive.value) enhancedActive.value = true
        // No local file identity survives process death. This flag is set only
        // after an authenticated PAUSED reply from the helper.
        enhancedPaused.value = true
        setEnhancedPaused(context, true)
    }

    /**
     * Reconcile only an authenticated idle reply. Network failures and
     * unavailable helpers must never be interpreted as a stopped recording.
     * STARTED wakes still supply the filename needed to adopt a new session.
     */
    fun reconcileAuthenticatedIdle(context: Context) {
        // A paused segment has already closed its file. An authenticated IDLE
        // reply must still clear its paused UI and notification.
        if (!shellLifecycle.stoppedByUser() && !enhancedPaused.value && !enhancedActive.value) return
        enhancedActive.value = false
        enhancedPaused.value = false
        dismiss(context)
        runCatching { context.stopService(Intent(context, OverlayControlService::class.java)) }
    }

    fun shellStoppedByUser(context: Context) {
        if (!shellLifecycle.stoppedByUser() && !enhancedPaused.value) return
        enhancedActive.value = false
        enhancedPaused.value = false
        dismiss(context)
        runCatching { context.stopService(Intent(context, OverlayControlService::class.java)) }
    }

}
