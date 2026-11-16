package com.vrec.app.experimental.adb

import android.content.Context
import com.vrec.app.shizuku.ShizukuSession
import com.vrec.app.shizuku.ShizukuSessionRegistry
import com.vrec.app.shizuku.ShizukuHostHooks
import com.vrec.app.shizuku.ShizukuOption
import com.vrec.app.shizuku.ShizukuPreferences

/**
 * Migration adapter. Preserves VRec's authorization files and boot-scoped choices.
 * Do not initialize a second live ADB connection alongside the legacy engine.
 * The calling service must retire legacy transport before adopting this instance.
 */
internal object ShizukuHostAdapter {
    @Volatile private var active: ShizukuSession? = null

    fun core(context: Context): ShizukuSession = active ?: synchronized(this) {
        active ?: create(context.applicationContext).also {
            active = it
            ShizukuSessionRegistry.install(it)
        }
    }

    fun current(): ShizukuSession? = active

    suspend fun shutdown() {
        val old = synchronized(this) { active.also { active = null } }
        old?.let { ShizukuSessionRegistry.uninstall(it); it.quit() }
    }

    fun create(context: Context): ShizukuSession {
        val app = context.applicationContext
        return ShizukuSession(app, Choices(app),
            object : ShizukuHostHooks {
                override suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {
                    if (enabled) AdbSelfRecovery.grantSecureSettings(app)
                    else AdbSelfRecovery.revokeSecureSettings(app)
                }
            },
        )
    }

    private class Choices(private val app: Context) : ShizukuPreferences {
        private val timestamps = app.getSharedPreferences("vrec_adb_user_choices", Context.MODE_PRIVATE)
        override fun enabled(option: ShizukuOption) = when (option) {
            ShizukuOption.AUTO_PAIR -> AdbUserChoices.autoPairCode(app)
            ShizukuOption.WATCHDOG -> AdbUserChoices.connectionWatchdog(app)
            ShizukuOption.START_ON_BOOT -> AdbUserChoices.startOnBoot(app)
            ShizukuOption.RESTORE_AFTER_RESTART -> AdbUserChoices.restoreAfterRestart(app)
            ShizukuOption.TCP_5555 -> AdbUserChoices.tcpMode(app)
        }
        override fun setEnabled(option: ShizukuOption, enabled: Boolean) {
            when (option) {
                ShizukuOption.AUTO_PAIR -> AdbUserChoices.setAutoPairCode(app, enabled)
                ShizukuOption.WATCHDOG -> AdbLifecycle.configureWatchdog(app, enabled)
                ShizukuOption.START_ON_BOOT -> {
                    AdbUserChoices.setStartOnBoot(app, enabled)
                    AdbLifecycle.configureWatchdog(app, AdbUserChoices.connectionWatchdog(app))
                }
                ShizukuOption.RESTORE_AFTER_RESTART -> AdbUserChoices.setRestoreAfterRestart(app, enabled)
                ShizukuOption.TCP_5555 -> AdbUserChoices.setTcpMode(app, enabled)
            }
        }
        override fun manuallyStopped() = AdbUserChoices.manuallyStopped(app)
        override fun setManuallyStopped(stopped: Boolean) = AdbUserChoices.setManuallyStopped(app, stopped)
        override fun lastTlsPort() = AdbUserChoices.lastVerifiedPort(app)
        override fun setLastTlsPort(port: Int) = AdbUserChoices.rememberVerifiedPort(app, port)
        override fun lastVerifiedAt() = timestamps.getLong("core_verified_at", 0L)
        override fun setLastVerifiedAt(timeMs: Long) {
            timestamps.edit().putLong("core_verified_at", timeMs).apply()
        }
        override fun wasAuthorized() = AdbUserChoices.lastVerifiedWirelessBoot(app) >= 0
        override fun setAuthorized() = AdbUserChoices.rememberVerifiedWirelessBoot(app)
    }
}
