package com.vrec.app.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.MainActivity
import com.vrec.app.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Per-recording standard fallback while the optional ADB helper is unavailable.
 * Never toggles the saved recorder method or enables Android debugging.
 */
object AdbFallbackNotice {
    private const val CHANNEL = "vrec_adb_fallback"
    private const val NOTIFICATION_ID = 86
    val active = MutableStateFlow(false)
    val handoffInProgress = MutableStateFlow(false)
    val enhancedActive = MutableStateFlow(false)
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
    fun shellRecordingStarted(context: Context, file: String) {
        if (!shellLifecycle.started(file)) return
        enhancedActive.value = true
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
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w("Shell recording notification unavailable", it) }
    }

    fun shellRecordingEnded(context: Context, file: String) {
        if (!shellLifecycle.ended(file)) return
        enhancedActive.value = false
        dismiss(context)
        runCatching { context.stopService(Intent(context, OverlayControlService::class.java)) }
    }

    fun notifyEnhancedStarted(context: Context) {
        dismiss(context)
        enhancedActive.value = true
        if (Prefs.showOverlay(context) && !com.vrec.app.calls.CallMonitor.overlayWasDismissed()) {
            runCatching {
                context.startService(Intent(context, OverlayControlService::class.java)
                    .setAction(OverlayControlService.ACTION_SHOW))
            }.onFailure { Log.w("Unable to show enhanced recording overlay", it) }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "ADB recording status", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Switched to enhanced ADB recording")
            .setContentText("Standard segment saved. Enhanced capture is now active for this call.")
            .setContentIntent(PendingIntent.getActivity(
                context, 86,
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_ADB_SETUP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notice) }
            .onFailure { Log.w("Unable to confirm enhanced capture handoff", it) }
    }

    fun notifyStandardStarted(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            // The active fallback is also visible on Home without notification access.
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "ADB recording fallback", NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Prompts to restore enhanced recording while standard capture continues."
                }
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val setupIntent = PendingIntent.getActivity(
            context, 86,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_ADB_SETUP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        // Android, not VRec, owns the user-controlled debugging authorization.
        val debuggingIntent = PendingIntent.getActivity(
            context, 87,
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("ADB helper unavailable · Standard recording")
            .setContentText("Recording with the microphone. Enable debugging to restore ADB for future calls.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "This call is being recorded using the standard microphone method. " +
                    "To restore the enhanced helper for future calls, enable USB debugging " +
                    "and complete the one-time trusted USB ADB host setup (adb tcpip 5555) if needed."
            ))
            .setContentIntent(setupIntent)
            .addAction(0, "Enable debugging", debuggingIntent)
            .addAction(0, "ADB setup", setupIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w("Unable to show ADB fallback notification", it) }
    }
}
