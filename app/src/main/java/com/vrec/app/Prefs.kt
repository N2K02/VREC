package com.vrec.app

import android.content.Context
import android.content.SharedPreferences
import com.vrec.app.experimental.adb.ExperimentalRecording

/**
 * All of VREC's settings in one place. [AudioSource] values below are stored by name.
 */
object Prefs {
    private const val NAME = "vrec"

    /** Same persisted, opt-in recording master preference as stable VRec. */
    const val RECORDING_ENABLED = "recording_enabled"

    /** Record calls automatically when the master switch is on. */
    const val AUTO_RECORD = "auto_record"

    /**
     * How VREC picks the microphone source. "auto" tries the ordered fallback chain and keeps
     * the first that produces sound; otherwise the single named [AudioSource] is used.
     */
    const val SOURCE_MODE = "source_mode" // "auto" or an AudioSource name
    const val SOURCE_MODE_AUTO = "auto"

    /** Put the call on loudspeaker while recording so the mic can pick up the other party. */
    const val AUTO_SPEAKER = "auto_speaker"

    /** Switch the audio system to in-communication mode while recording (helps some sources). */
    const val IN_COMM_MODE = "in_comm_mode"

    /** Also record VoIP calls (WhatsApp etc.) detected through Notification access. */
    const val RECORD_VOIP = "record_voip"

    /** Discard a finished recording that turned out to be silent. */
    const val DISCARD_SILENT = "discard_silent"

    const val SHOW_OVERLAY = "show_overlay"
    /** Default enabled for new installs; an explicit user choice remains authoritative. */
    const val SHOW_OVERLAY_DEFAULT = true

    /** Advanced Telecom detection is only active in the explicitly selected ADB mode. */
    const val EXPERIMENTAL_CALL_DETECTION = "experimental_call_detection"
    fun experimentalCallDetection(c: Context) =
        ExperimentalRecording.method(c) == ExperimentalRecording.Method.BUILT_IN_ADB &&
            of(c).getBoolean(EXPERIMENTAL_CALL_DETECTION, false)

    fun showOverlay(c: Context) = of(c).getBoolean(SHOW_OVERLAY, SHOW_OVERLAY_DEFAULT)

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun recordingEnabled(c: Context) = of(c).getBoolean(RECORDING_ENABLED, false)
    fun setRecordingEnabled(c: Context, enabled: Boolean) {
        of(c).edit().putBoolean(RECORDING_ENABLED, enabled).apply()
    }

    fun autoRecord(c: Context) = of(c).getBoolean(AUTO_RECORD, true)
    fun autoSpeaker(c: Context) = of(c).getBoolean(AUTO_SPEAKER, false)
    fun inCommMode(c: Context) = of(c).getBoolean(IN_COMM_MODE, true)
    fun recordVoip(c: Context) = of(c).getBoolean(RECORD_VOIP, false)
    fun discardSilent(c: Context) = of(c).getBoolean(DISCARD_SILENT, false)

    fun sourceMode(c: Context): String = of(c).getString(SOURCE_MODE, SOURCE_MODE_AUTO) ?: SOURCE_MODE_AUTO
}
