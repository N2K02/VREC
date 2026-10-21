package com.vrec.app

import android.content.Context
import android.content.SharedPreferences

/**
 * All of VREC's settings in one place. [AudioSource] values below are stored by name.
 */
object Prefs {
    private const val NAME = "vrec"

    /** Record calls automatically as they happen. */
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

    fun showOverlay(c: Context) = of(c).getBoolean(SHOW_OVERLAY, false)

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoRecord(c: Context) = of(c).getBoolean(AUTO_RECORD, true)
    fun autoSpeaker(c: Context) = of(c).getBoolean(AUTO_SPEAKER, false)
    fun inCommMode(c: Context) = of(c).getBoolean(IN_COMM_MODE, true)
    fun recordVoip(c: Context) = of(c).getBoolean(RECORD_VOIP, false)
    fun discardSilent(c: Context) = of(c).getBoolean(DISCARD_SILENT, false)

    fun sourceMode(c: Context): String = of(c).getString(SOURCE_MODE, SOURCE_MODE_AUTO) ?: SOURCE_MODE_AUTO
}
