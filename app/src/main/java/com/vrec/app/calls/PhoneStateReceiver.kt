package com.vrec.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * SIM call detection. Android delivers RINGING / OFFHOOK / IDLE here as the call progresses.
 * The incoming number is only present when the OS chooses to include it; VREC records either way.
 * Also re-arms nothing on boot/update — recording is driven live by call state — but the entry is
 * kept so the app's process is around to receive the first call-state broadcast after a reboot.
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
                // Start on boot only controls BOOT_COMPLETED. An app upgrade
                // may still rearm already-authorized background recovery.
                // Credential-protected ADB keys require device unlock.
                if (!choices.manuallyStopped(app) &&
                    (intent.action != Intent.ACTION_BOOT_COMPLETED || choices.startOnBoot(app))) {
                    com.vrec.app.experimental.adb.AdbLifecycle.startBackground(app)
                    com.vrec.app.experimental.adb.AdbLifecycle.requestRecovery(app,
                        intent.action ?: "system restart")
                }
            }
        }
    }
}
