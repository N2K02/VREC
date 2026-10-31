package com.vrec.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * SIM call detection. Android delivers RINGING / OFFHOOK / IDLE here as the call progresses.
 * The incoming number is only present when the OS chooses to include it; VREC records either way.
 * BOOT_COMPLETED recovery respects the Start on boot preference and deliberate Quit;
 * MY_PACKAGE_REPLACED can rearm existing authorized recovery after updates.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                CallMonitor.onSimState(context, state, number)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext
                val choices = com.vrec.app.experimental.adb.AdbUserChoices
                // A disabled boot option also blocks post-reboot worker or
                // update-driven recovery until the user has opened VRec.
                // Credential-protected ADB keys require device unlock.
                if (!choices.manuallyStopped(app) && choices.backgroundRecoveryAllowed(app)) {
                    com.vrec.app.experimental.adb.AdbLifecycle.startBackground(app)
                    com.vrec.app.experimental.adb.AdbLifecycle.requestRecovery(app,
                        intent.action ?: "system restart")
                }
            }
        }
    }
}
