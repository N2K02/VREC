package com.vrec.app

import com.vrec.app.recording.CallDirection

/** Display values shared by Android notification builder and JVM tests. */
internal object RecordingNotificationPresentation {
    fun title(caller: String, paused: Boolean): String =
        if (paused) "Paused · $caller" else "Recording now · $caller"

    fun description(info: LiveRecordingInfo, paused: Boolean, elapsed: String): String {
        val flow = when (info.direction) {
            CallDirection.INCOMING -> "Incoming"
            CallDirection.OUTGOING -> "Outgoing"
            else -> "Call"
        }
        return listOf(info.source, flow,
            if (paused) "Paused at $elapsed" else "Recording").joinToString(" · ")
    }

    fun showsChronometer(paused: Boolean, startedAt: Long?): Boolean =
        !paused && startedAt != null
}
