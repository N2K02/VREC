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
 * Setup blockers a simple user can't see, and the opt-in restore-after-restart.
 *
 * Restore is OFF unless the user turns on "Restore after restart". Only then is
 * WRITE_SECURE_SETTINGS requested (through a verified UID 2000 shell), and only
 * after a reboot does VRec switch Wireless debugging back on; never when the
 * user turned it off during the same boot (see [RestoreDecision]). Every
 * restore is verified: the setting is read back, shell UID 2000 must be
 * re-verified, and the recording helper must answer. A paired key is not
 * assumed to still be authorized.
 */
object AdbSelfRecovery {
    private const val SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun inputs(context: Context) = RestoreDecision.Inputs(
        optedIn = AdbUserChoices.restoreAfterRestart(context),
        sdk = Build.VERSION.SDK_INT,
        paired = ExperimentalRecording.wirelessWasPaired(context),
        autoRecoveryAllowed = ExperimentalRecording.recoveryAuthorization(context).allowed,
        hasSecureSettings = hasSecureSettings(context),
        onWifi = onWifi(context),
        wirelessDebuggingOn = wirelessDebuggingOn(context),
        currentBoot = AdbUserChoices.bootCount(context),
        lastVerifiedBoot = AdbUserChoices.lastVerifiedWirelessBoot(context),
        awaitingManualRetry = ExperimentalRecording.awaitingManualRetry,
    )

    /** True when restore is opted in and could act on this phone (for UI and scheduling). */
    fun canSelfRestore(context: Context): Boolean =
        AdbUserChoices.restoreAfterRestart(context) && Build.VERSION.SDK_INT >= 30 &&
            hasSecureSettings(context) && ExperimentalRecording.wirelessWasPaired(context) &&
            ExperimentalRecording.recoveryAuthorization(context).allowed

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Only for the opted-in restore feature. Caller must hold a verified UID 2000
     * session; the grant is checked with PackageManager afterwards, never assumed.
     */
    suspend fun grantSecureSettings(context: Context): Boolean {
        if (hasSecureSettings(context)) return true
        if (!AdbUserChoices.restoreAfterRestart(context)) return false
        AdbTransport.exec("pm grant ${context.packageName} $SECURE_SETTINGS", timeoutMs = 10_000)
        return hasSecureSettings(context).also {
            Log.i("ADB: automatic restore permission ${if (it) "granted" else "refused by Android"}")
        }
    }

    /** When the user turns restore off: give the permission back (needs the verified shell). */
    suspend fun revokeSecureSettings(context: Context): Boolean {
        if (!hasSecureSettings(context)) return true
        val link = ExperimentalRecording.status.value
        if (!link.connected || link.shellUid != 2000) return false
        AdbTransport.exec("pm revoke ${context.packageName} $SECURE_SETTINGS", timeoutMs = 10_000)
        return !hasSecureSettings(context)
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

    enum class RestoreOutcome {
        /** Restore not opted in, unsupported, not paired, disconnected by user, or no permission. */
        NOT_ELIGIBLE,
        NO_WIFI,
        /** Wireless debugging is off in the same boot it was verified on: the user's choice. */
        USER_DISABLED,
        /** An earlier authorization failure is waiting for the user's Retry. */
        MANUAL_RETRY_REQUIRED,
        /** Android didn't accept the setting change. */
        SETTING_REJECTED,
        /** Reconnect or UID 2000 or helper not verified (includes a revoked key). */
        NOT_VERIFIED,
        RESTORED,
    }

    fun outcomeFor(verdict: RestoreDecision.Verdict): RestoreOutcome? = when (verdict) {
        RestoreDecision.Verdict.RESTORE, RestoreDecision.Verdict.ALREADY_ON -> null
        RestoreDecision.Verdict.NO_WIFI -> RestoreOutcome.NO_WIFI
        RestoreDecision.Verdict.USER_TURNED_OFF -> RestoreOutcome.USER_DISABLED
        RestoreDecision.Verdict.MANUAL_RETRY_REQUIRED -> RestoreOutcome.MANUAL_RETRY_REQUIRED
        else -> RestoreOutcome.NOT_ELIGIBLE
    }

    /**
     * Switch Wireless debugging back on after a reboot (if [RestoreDecision]
     * allows) and let the paired discovery reconnect. Success requires the
     * setting to read back as on, a re-verified shell UID 2000 session and the
     * recording helper answering. A failed attempt stops discovery.
     */
    suspend fun restoreWireless(context: Context, verifyTimeoutMs: Long = 25_000L): RestoreOutcome {
        val app = context.applicationContext
        val verdict = RestoreDecision.decide(inputs(app))
        outcomeFor(verdict)?.let { return it }
        if (verdict == RestoreDecision.Verdict.RESTORE) {
            runCatching { Settings.Global.putInt(app.contentResolver, ADB_WIFI_ENABLED, 1) }
            delay(500L)
            if (!wirelessDebuggingOn(app)) {
                Log.w("ADB restore: Android did not accept turning Wireless debugging on")
                return RestoreOutcome.SETTING_REJECTED
            }
            Log.i("ADB restore: Wireless debugging re-enabled after restart; waiting for verified reconnect")
        }
        // Restore has its own backoff, so bypass the discovery throttle here.
        LocalWirelessAdb.start(app, userInitiated = true)
        val deadline = SystemClock.elapsedRealtime() + verifyTimeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(1_000L)
            val link = ExperimentalRecording.status.value
            if (link.connected && link.shellUid == 2000 && ExperimentalCaptureDaemon.probe(app)) {
                Log.i("ADB restore: shell UID 2000 and helper verified after restart")
                return RestoreOutcome.RESTORED
            }
            // A revoked key or denied prompt ends here, not in a loop.
            if (AdbFailurePolicy.requiresManualRetry(link.lastFailure)) break
        }
        val link = ExperimentalRecording.status.value
        val stage = when {
            AdbFailurePolicy.requiresManualRetry(link.lastFailure) -> "authorization ${link.lastFailure}"
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
