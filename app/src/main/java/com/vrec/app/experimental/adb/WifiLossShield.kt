package com.vrec.app.experimental.adb

import android.content.Context
import com.vrec.app.Log

/**
 * "Survive Wi-Fi loss": keeps USB debugging (`adb_enabled`, no cable needed)
 * on, so adbd is not restarted when Wi-Fi drops; an adbd restart kills the
 * recording helper. Adapted from JemRec's off-Wi-Fi shield (Apache-2.0).
 *
 * The helper itself enforces the shield every 10 seconds; this switch tells it
 * not to through a shell-owned marker file it checks on each tick. Turning the
 * switch off never turns USB debugging off: it only stops VRec forcing it on.
 */
internal object WifiLossShield {
    const val OFF_MARKER = "/data/local/tmp/vrec-shield-off"

    fun onCommand(): String =
        "rm -f $OFF_MARKER && settings put global adb_enabled 1 && echo VREC_SHIELD_ON"

    fun offCommand(): String = "touch $OFF_MARKER && echo VREC_SHIELD_OFF"

    /** True when the helper confirmed the choice; false applies at the next helper start. */
    fun applied(on: Boolean, output: String?): Boolean =
        output.orEmpty().lineSequence().map(String::trim)
            .any { it == if (on) "VREC_SHIELD_ON" else "VREC_SHIELD_OFF" }

    suspend fun apply(context: Context): Boolean {
        val on = AdbUserChoices.wifiLossShield(context.applicationContext)
        val ok = applied(on, AdbTransport.exec(if (on) onCommand() else offCommand(),
            timeoutMs = 5_000).getOrNull())
        if (!ok) Log.w("Wi-Fi loss shield: choice will apply at the next helper start")
        return ok
    }
}
