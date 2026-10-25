package com.vrec.app.experimental.daemon

import android.content.Context
import android.os.Build
import com.vrec.app.Prefs
import com.vrec.app.experimental.adb.ExperimentalRecording

/** Independently selected privileged SIM capture path. Shell UID is always required. */
enum class SimCaptureSource(val wireValue: Int, val description: String) {
    VOICE_CALL(0, "Both sides via VOICE_CALL (recommended)"),
    UPLINK(1, "Outgoing call audio only (diagnostics)"),
    DOWNLINK(2, "Incoming call audio only (diagnostics)"),
    DUAL(3, "Separate uplink/downlink stereo (device-dependent)");

    companion object {
        fun from(value: String?): SimCaptureSource =
            entries.firstOrNull { it.name == value } ?: VOICE_CALL
    }
}

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

/** Capture selection is independent of VRec's standard microphone source preference. */
object EnhancedCapturePreferences {
    private const val KEY = "enhanced_sim_capture_source"
    fun simSource(context: Context): SimCaptureSource =
        SimCaptureSource.from(Prefs.of(context).getString(KEY, SimCaptureSource.VOICE_CALL.name))

    fun setSimSource(context: Context, source: SimCaptureSource) {
        Prefs.of(context).edit().putString(KEY, source.name).apply()
    }

    fun voipPackages(context: Context): Set<String> = EnhancedVoipPolicy.packages(
        enabled = Prefs.recordVoip(context),
        adbSelected = ExperimentalRecording.method(context) == ExperimentalRecording.Method.BUILT_IN_ADB,
        sdk = Build.VERSION.SDK_INT,
    )
}
