package com.vrec.app.experimental.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbCoreRegistry
import dev.n2k02.adb.core.AdbHostHooks
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/**
 * Migration adapter. Preserves VRec's authorization files and boot-scoped choices.
 * Do not initialize a second live ADB connection alongside the legacy engine.
 * The calling service must retire legacy transport before adopting this instance.
 */
internal object SharedAdbHostAdapter {
    @Volatile private var active: AdbCore? = null

    fun core(context: Context): AdbCore = active ?: synchronized(this) {
        active ?: create(context.applicationContext).also {
            active = it
            AdbCoreRegistry.install(it)
        }
    }

    fun current(): AdbCore? = active

    suspend fun shutdown() {
        val old = synchronized(this) { active.also { active = null } }
        old?.let { AdbCoreRegistry.uninstall(it); it.quit() }
    }

    fun create(context: Context): AdbCore {
        val app = context.applicationContext
        val identity = AdbIdentitySource { ctx ->
            val existing = AdbIdentity.getOrCreate(ctx)
            CoreIdentity(existing.privateKey, existing.certificate)
        }
        return AdbCore(
            LibAdbWire(app, identity, "VREC"), Choices(app), LocalAdbDiscovery(app),
            object : AdbHostHooks {
                override suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {
                    if (enabled) AdbSelfRecovery.grantSecureSettings(app)
                    else AdbSelfRecovery.revokeSecureSettings(app)
                }
            },
        )
    }

    private class Choices(private val app: Context) : AdbPreferenceStore {
        private val timestamps = app.getSharedPreferences("vrec_adb_user_choices", Context.MODE_PRIVATE)
        override fun enabled(option: AdbOption) = when (option) {
            AdbOption.AUTO_PAIR -> AdbUserChoices.autoPairCode(app)
            AdbOption.WATCHDOG -> AdbUserChoices.connectionWatchdog(app)
            AdbOption.START_ON_BOOT -> AdbUserChoices.startOnBoot(app)
            AdbOption.RESTORE_AFTER_RESTART -> AdbUserChoices.restoreAfterRestart(app)
            AdbOption.TCP_5555 -> AdbUserChoices.tcpMode(app)
        }
        override fun setEnabled(option: AdbOption, enabled: Boolean) {
            when (option) {
                AdbOption.AUTO_PAIR -> AdbUserChoices.setAutoPairCode(app, enabled)
                AdbOption.WATCHDOG -> AdbLifecycle.configureWatchdog(app, enabled)
                AdbOption.START_ON_BOOT -> {
                    AdbUserChoices.setStartOnBoot(app, enabled)
                    AdbLifecycle.configureWatchdog(app, AdbUserChoices.connectionWatchdog(app))
                }
                AdbOption.RESTORE_AFTER_RESTART -> AdbUserChoices.setRestoreAfterRestart(app, enabled)
                AdbOption.TCP_5555 -> AdbUserChoices.setTcpMode(app, enabled)
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
