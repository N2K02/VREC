package com.vrec.app.experimental.adb

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.SystemClock
import com.vrec.app.Log
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import kotlinx.coroutines.delay

/**
 * Setup blockers a simple user can't see, and restore-after-restart.
 *
 * Once a verified shell (UID 2000) grants VRec WRITE_SECURE_SETTINGS, VRec can
 * turn Wireless debugging back on by itself after a reboot (on Wi-Fi), and the
 * existing paired-wireless flow reconnects and restarts the helper. This only
 * re-uses a pairing the user already approved; it never creates new ADB trust.
 * Phones without Wireless debugging (Android 10 and older) cannot do this and
 * need the USB host again after every restart.
 */
object AdbSelfRecovery {
    private const val SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun canSelfRestore(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && hasSecureSettings(context) &&
            ExperimentalRecording.wirelessWasPaired(context)

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Caller must hold a verified UID 2000 session. Verified afterwards, never assumed. */
    suspend fun grantSecureSettings(context: Context): Boolean {
        if (hasSecureSettings(context)) return true
        AdbTransport.exec("pm grant ${context.packageName} $SECURE_SETTINGS", timeoutMs = 10_000)
        return hasSecureSettings(context).also {
            Log.i("ADB: automatic restore permission ${if (it) "granted" else "refused by Android"}")
        }
    }

    fun developerOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    }.getOrDefault(true)

    fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    fun wirelessDebuggingOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    enum class RestoreOutcome { NOT_ELIGIBLE, NO_WIFI, SETTING_REJECTED, NOT_VERIFIED, RESTORED }

    /**
     * After a restart: switch Wireless debugging back on and let the paired
     * discovery reconnect. Never trusts the setting write: the value is read
     * back, and success requires the recording helper itself to answer.
     * A failed attempt stops discovery so it doesn't keep running.
     */
    suspend fun restoreWireless(context: Context, verifyTimeoutMs: Long = 25_000L): RestoreOutcome {
        val app = context.applicationContext
        if (!canSelfRestore(app)) return RestoreOutcome.NOT_ELIGIBLE
        if (!onWifi(app)) return RestoreOutcome.NO_WIFI
        if (!wirelessDebuggingOn(app)) {
            runCatching { Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, 1) }
            delay(500L)
            if (!wirelessDebuggingOn(app)) {
                Log.w("ADB restore: Android did not accept turning Wireless debugging on")
                return RestoreOutcome.SETTING_REJECTED
            }
            Log.i("ADB restore: Wireless debugging re-enabled; waiting for verified reconnect")
        }
        // Restore has its own backoff, so bypass the discovery throttle here.
        LocalWirelessAdb.start(app, userInitiated = true)
        val deadline = SystemClock.elapsedRealtime() + verifyTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(1_000L)
            if (ExperimentalCaptureDaemon.probe(app)) {
                Log.i("ADB restore: helper verified after restart")
                return RestoreOutcome.RESTORED
            }
        }
        val link = ExperimentalRecording.status.value
        val stage = when {
            link.connected && link.shellUid == 2000 -> "shell verified, helper did not answer"
            LocalWirelessAdb.status.value.connectPort != null -> "service found, shell not verified"
            else -> "no Wireless debugging service found"
        }
        Log.w("ADB restore: not verified ($stage)")
        if (!link.connected) LocalWirelessAdb.stop(app)
        return RestoreOutcome.NOT_VERIFIED
    }

    /** Opens Developer options with the Wireless debugging row highlighted where supported. */
    fun openWirelessDebugging(context: Context) {
        val args = Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") }
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .putExtra(":settings:show_fragment_args", args)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { openDeviceInfo(context) }
    }

    /** "About phone", where Build number is tapped 7 times to unlock Developer options. */
    fun openDeviceInfo(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
