package com.vrec.app.experimental.adb

import android.content.Context
import com.vrec.app.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps Android from forgetting VRec's ADB pairing.
 *
 * Adapted from JemRec's PairingTimeout (Apache-2.0, see shellserver/NOTICE).
 * Android deletes an ADB key older than `adb_allowed_connection_time` (7 days
 * when unset) at the next write of its key list - a USB cable plugged in or
 * out is enough - and Wireless debugging connections never refresh that age.
 * The running helper is unaffected, but its next start (after a reboot or an
 * adbd restart) is refused and the phone must be paired again. 0 is what
 * Developer options' "Disable adb authorization timeout" writes.
 *
 * The previous value is recorded in shell-owned /data/local/tmp BEFORE the
 * write, so it can be put back by VRec (switch turned off) or by the helper's
 * custodian after VRec is uninstalled. The value is global and also keeps
 * other paired hosts authorized, which is why it is a user switch.
 */
internal object PairingTimeout {
    const val SETTING = "adb_allowed_connection_time"
    const val RECORD = "/data/local/tmp/vrec-pairing-timeout"

    enum class Outcome { KEPT, ALREADY_OFF, GIVEN_BACK, NOTHING_TO_GIVE_BACK, FAILED }

    /** Last result, for the ADB settings card. */
    val last = MutableStateFlow<Outcome?>(null)

    /** Idempotent: records the current value once, then writes 0 and reads it back. */
    fun keepCommand(): String =
        "cur=\$(settings get global $SETTING); " +
            "if [ \"\$cur\" = 0 ]; then echo VREC_PAIRING_ALREADY_OFF; else " +
            "{ [ -e $RECORD ] || printf %s \"\$cur\" > $RECORD; } && " +
            "settings put global $SETTING 0 && " +
            "[ \"\$(settings get global $SETTING)\" = 0 ] && echo VREC_PAIRING_KEPT; fi"

    /**
     * Puts the recorded value back only while the setting still reads 0: a
     * value the user changed since is left alone. A recorded value that is not
     * a plain number becomes "delete" (Android's default), never shell text.
     */
    fun giveBackCommand(): String =
        "if [ -e $RECORD ]; then v=\$(cat $RECORD); " +
            "case \"\$v\" in null) ;; ''|*[!0-9]*) v=null;; esac; " +
            "if [ \"\$(settings get global $SETTING)\" = 0 ]; then " +
            "if [ \"\$v\" = null ]; then settings delete global $SETTING >/dev/null; " +
            "else settings put global $SETTING \"\$v\"; fi; fi; " +
            "rm -f $RECORD && echo VREC_PAIRING_GIVEN_BACK; " +
            "else echo VREC_PAIRING_NOTHING; fi"

    fun parse(output: String?): Outcome {
        val lines = output.orEmpty().lineSequence().map(String::trim).toSet()
        return when {
            "VREC_PAIRING_KEPT" in lines -> Outcome.KEPT
            "VREC_PAIRING_ALREADY_OFF" in lines -> Outcome.ALREADY_OFF
            "VREC_PAIRING_GIVEN_BACK" in lines -> Outcome.GIVEN_BACK
            "VREC_PAIRING_NOTHING" in lines -> Outcome.NOTHING_TO_GIVE_BACK
            else -> Outcome.FAILED
        }
    }

    /**
     * Applies the user's choice over the verified shell. Called after every
     * successful helper start and when the switch changes; never throws.
     */
    suspend fun apply(context: Context): Outcome {
        val keep = AdbUserChoices.keepPaired(context.applicationContext)
        val output = AdbTransport.exec(if (keep) keepCommand() else giveBackCommand(),
            timeoutMs = 10_000).getOrNull()
        val outcome = parse(output)
        last.value = outcome
        if (outcome == Outcome.KEPT) Log.i("ADB pairing: authorization timeout turned off")
        if (outcome == Outcome.GIVEN_BACK) Log.i("ADB pairing: authorization timeout restored")
        if (outcome == Outcome.FAILED) Log.w("ADB pairing: could not apply the authorization timeout choice")
        return outcome
    }
}
