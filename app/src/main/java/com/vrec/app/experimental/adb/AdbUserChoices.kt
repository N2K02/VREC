package com.vrec.app.experimental.adb

import android.content.Context
import android.provider.Settings

/**
 * Security-relevant ADB preferences. TCP/5555 defaults ON for verified shell setup;
 * reboot restore remains OFF unless explicitly enabled.
 *
 * - Restore after restart: VRec may switch Wireless debugging back on after a
 *   reboot (Android turns it off on reboot) to restart the recording helper.
 *   Never after the user turned it off themselves during the same boot.
 * - TCP/5555 mode: after verified Wireless ADB, VRec moves adbd to TCP/5555
 *   automatically for durable local reconnects. The user can switch it off;
 *   TCP/5555 may be reachable by other devices on the same network.
 */
object AdbUserChoices {
    private const val PREFS = "vrec_adb_user_choices"
    private const val RESTORE_AFTER_RESTART = "restore_after_restart"
    private const val TCP_MODE = "tcp_5555_mode"
    private const val VERIFIED_BOOT = "last_verified_wireless_boot"
    private const val VERIFIED_PORT = "last_verified_wireless_port"
    private const val PORT_BOOT = "wireless_port_boot"
    private const val AUTO_PAIR = "automatic_pairing_code"
    private const val MANUALLY_STOPPED = "manual_quit"
    private const val CONNECTION_WATCHDOG = "connection_watchdog"
    private const val START_ON_BOOT = "start_on_boot"

    /** Enables periodic in-process recovery and the WorkManager safety net. */
    fun connectionWatchdog(context: Context): Boolean =
        prefs(context).getBoolean(CONNECTION_WATCHDOG, true)
    fun setConnectionWatchdog(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(CONNECTION_WATCHDOG, enabled).apply()
    }

    /** Controls BOOT_COMPLETED recovery, distinct from Wireless debugging restore. */
    fun startOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(START_ON_BOOT, true)
    fun setStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(START_ON_BOOT, enabled).apply()
    }


    /** Automatic with existing user-enabled Accessibility: inspect only Android's pairing dialog. */
    fun autoPairCode(context: Context): Boolean = prefs(context).getBoolean(AUTO_PAIR, true)
    fun setAutoPairCode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(AUTO_PAIR, enabled).apply()
    }

    /** A deliberate Quit must survive a worker/boot receiver starting the process. */
    fun manuallyStopped(context: Context): Boolean =
        prefs(context).getBoolean(MANUALLY_STOPPED, false)
    fun setManuallyStopped(context: Context, stopped: Boolean) {
        prefs(context).edit().putBoolean(MANUALLY_STOPPED, stopped).commit()
    }

    /** Reuse a *verified* TLS endpoint only during the current Android boot.
     * The port may rotate at any moment: consumers must reconnect and verify UID 2000. */
    fun rememberVerifiedPort(context: Context, port: Int) {
        if (port !in 1..65535) return
        val boot = bootCount(context)
        if (boot < 0) return
        prefs(context).edit().putInt(VERIFIED_PORT, port).putInt(PORT_BOOT, boot).apply()
    }

    /** Pure policy: never reuse a TLS port across reboot or with unknown boot count. */
    internal fun reusablePort(port: Int, rememberedBoot: Int, currentBoot: Int): Int? =
        port.takeIf { it in 1..65535 && currentBoot >= 0 && rememberedBoot == currentBoot }

    fun lastVerifiedPort(context: Context): Int? = reusablePort(
        prefs(context).getInt(VERIFIED_PORT, -1),
        prefs(context).getInt(PORT_BOOT, -1),
        bootCount(context),
    )

    fun clearVerifiedPort(context: Context, port: Int) {
        if (lastVerifiedPort(context) == port) {
            prefs(context).edit().remove(VERIFIED_PORT).remove(PORT_BOOT).apply()
        }
    }

    fun restoreAfterRestart(context: Context): Boolean =
        prefs(context).getBoolean(RESTORE_AFTER_RESTART, false)

    fun setRestoreAfterRestart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(RESTORE_AFTER_RESTART, enabled).apply()
    }

    fun tcpMode(context: Context): Boolean = prefs(context).getBoolean(TCP_MODE, true)

    fun setTcpMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(TCP_MODE, enabled).apply()
    }

    /** Boot count Android reports; changes on every reboot. -1 if unavailable. */
    fun bootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    /** Recorded when Wireless debugging was last verified working (UID 2000). */
    fun rememberVerifiedWirelessBoot(context: Context) {
        prefs(context).edit().putInt(VERIFIED_BOOT, bootCount(context)).apply()
    }

    fun lastVerifiedWirelessBoot(context: Context): Int = prefs(context).getInt(VERIFIED_BOOT, -1)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Whether VRec may switch Wireless debugging back on. Pure, so every case is unit tested. */
object RestoreDecision {
    enum class Verdict {
        NOT_OPTED_IN, NOT_SUPPORTED, NOT_PAIRED, DISCONNECTED_BY_USER, NO_PERMISSION,
        NO_WIFI, ALREADY_ON, USER_TURNED_OFF, MANUAL_RETRY_REQUIRED, RESTORE,
    }

    data class Inputs(
        val optedIn: Boolean,
        val sdk: Int,
        val paired: Boolean,
        val autoRecoveryAllowed: Boolean,
        val hasSecureSettings: Boolean,
        val onWifi: Boolean,
        val wirelessDebuggingOn: Boolean,
        val currentBoot: Int,
        val lastVerifiedBoot: Int,
        val awaitingManualRetry: Boolean,
    )

    fun decide(i: Inputs): Verdict = when {
        !i.optedIn -> Verdict.NOT_OPTED_IN
        i.sdk < 30 -> Verdict.NOT_SUPPORTED
        !i.paired -> Verdict.NOT_PAIRED
        !i.autoRecoveryAllowed -> Verdict.DISCONNECTED_BY_USER
        i.awaitingManualRetry -> Verdict.MANUAL_RETRY_REQUIRED
        !i.hasSecureSettings -> Verdict.NO_PERMISSION
        !i.onWifi -> Verdict.NO_WIFI
        i.wirelessDebuggingOn -> Verdict.ALREADY_ON
        // Off in the same boot where it was verified on: the user (or a policy)
        // turned it off. Only a reboot, which Android itself uses to turn it off,
        // is a reason to switch it back on. Unknown boot counts are treated as
        // "same boot" so VRec never overrides the user by guessing.
        i.currentBoot < 0 || i.lastVerifiedBoot < 0 || i.currentBoot == i.lastVerifiedBoot ->
            Verdict.USER_TURNED_OFF
        else -> Verdict.RESTORE
    }
}
