package com.vrec.app.experimental.daemon

/** Fail closed on malformed or stale daemon health data. No recording is started by this check. */
internal object RecordingWatchHealth {
    data class Snapshot(
        val startedAt: Long,
        val watching: Boolean,
        val looperHealthy: Boolean,
        val mode: Int,
        val inCall: Boolean,
        val recordingSince: Long,
        val events: Long,
        val lastEventAt: Long,
        val offHooks: Long,
    )
    fun parse(line: String): Snapshot? {
        if (!line.startsWith("WATCH 1 ")) return null
        val entries = line.removePrefix("WATCH 1 ").split(' ').mapNotNull {
            val split = it.indexOf('=')
            if (split < 1) null else it.substring(0, split) to it.substring(split + 1)
        }.toMap()
        fun number(key: String) = entries[key]?.toLongOrNull()?.takeIf { it >= 0 }
        val watching = entries["watching"] ?: return null
        val looper = entries["looper"] ?: return null
        val inCall = entries["call"] ?: return null
        if (watching !in listOf("0", "1") || looper !in listOf("ok", "stuck") ||
            inCall !in listOf("0", "1")) return null
        val mode = number("mode")?.takeIf { it in 0L..2L }?.toInt() ?: return null
        return Snapshot(
            startedAt = number("since") ?: return null,
            watching = watching == "1",
            looperHealthy = looper == "ok",
            mode = mode,
            inCall = inCall == "1",
            recordingSince = number("recording") ?: return null,
            events = number("events") ?: return null,
            lastEventAt = number("lastevent") ?: return null,
            offHooks = number("offhooks") ?: return null,
        )
    }
    fun verdict(snapshot: Snapshot?, expectedMode: Int): String = when {
        snapshot == null -> "Recorder watch report unavailable or incompatible"
        !snapshot.watching -> "Recorder is running but not listening for calls"
        !snapshot.looperHealthy -> "Recorder call event loop is stuck"
        snapshot.mode != expectedMode -> "Recorder mode differs from the saved setting"
        snapshot.recordingSince > 0 && !snapshot.inCall -> "Recorder may be stuck recording after a call"
        else -> "Call watcher is healthy; a real test call is still needed to verify both voices"
    }
    fun healthy(snapshot: Snapshot?, expectedMode: Int): Boolean =
        snapshot != null && snapshot.watching && snapshot.looperHealthy &&
            snapshot.mode == expectedMode && (snapshot.recordingSince == 0L || snapshot.inCall)
}
