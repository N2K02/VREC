package com.vrec.app.experimental.daemon

import android.content.Context
import android.os.Build
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.ExperimentalRecording

/** The proven two-way SIM capture path; no user-selectable alternatives. */
internal const val SIM_VOICE_CALL_SOURCE = 0

/** Supported app packages, intentionally kept in an allowlist rather than querying other apps. */
object EnhancedVoipPolicy {
    val supportedPackages: Set<String> = linkedSetOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.viber.voip",
        "com.skype.raider", "com.google.android.apps.tachyon",
    )

    fun packages(enabled: Boolean, adbSelected: Boolean, sdk: Int): Set<String> =
        if (enabled && adbSelected && sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            supportedPackages else emptySet()

    fun isShellVoipCall(enabled: Boolean, adbSelected: Boolean, sdk: Int,
                        appPackage: String?): Boolean =
        appPackage != null && appPackage in packages(enabled, adbSelected, sdk)
}

/** Shared VoIP allowlist configuration; SIM always uses VOICE_CALL. */
object EnhancedCapturePreferences {
    fun voipPackages(context: Context): Set<String> = EnhancedVoipPolicy.packages(
        enabled = Prefs.recordVoip(context),
        adbSelected = true,
        sdk = Build.VERSION.SDK_INT,
    )
}
