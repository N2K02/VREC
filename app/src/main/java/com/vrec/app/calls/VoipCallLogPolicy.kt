package com.vrec.app.calls

import com.vrec.app.recording.CallDirection
import kotlin.math.abs

internal data class VoipCallLogEntry(
    val packageName: String?, val accountId: String?, val number: String?,
    val date: Long, val durationSeconds: Long, val type: Int,
)

/** Never infer direction or numbers from a different or ambiguous call session. */
internal object VoipCallLogPolicy {
    private fun uniqueSession(
        packageName: String, startedAt: Long, endedAt: Long, durationMs: Long,
        flow: String?, entries: List<VoipCallLogEntry>,
    ): List<VoipCallLogEntry>? {
        if (packageName.isBlank() || startedAt <= 0L || durationMs < 5_000L) return null
        val expectedType = when (flow) { "INCOMING" -> 1; "OUTGOING" -> 2; else -> null }
        // For an eight-second capture, a thirty-second call is NOT a match.
        // Longer calls permit modest recording/call-log timing differences.
        val allowedDriftMs = minOf(30_000L, maxOf(5_000L, durationMs / 2))
        val groups = entries.filter { row ->
            row.packageName == packageName && row.type in 1..2 &&
                (expectedType == null || row.type == expectedType) && row.durationSeconds > 0 &&
                startedAt - row.date in -15_000L..180_000L &&
                abs(durationMs - row.durationSeconds * 1000) <= allowedDriftMs &&
                abs(endedAt - (row.date + row.durationSeconds * 1000)) <= allowedDriftMs
        }.groupBy { listOf(it.accountId, it.date, it.durationSeconds, it.type) }
        // Multiple participants can share a group; different calls cannot.
        return groups.values.singleOrNull()
    }

    fun numbers(packageName: String, startedAt: Long, endedAt: Long, durationMs: Long,
                flow: String?, entries: List<VoipCallLogEntry>): List<String> =
        VoipIdentityPolicy.numbers(
            uniqueSession(packageName, startedAt, endedAt, durationMs, flow, entries)
                ?.mapNotNull { it.number }.orEmpty()
        )

    fun direction(packageName: String, startedAt: Long, endedAt: Long, durationMs: Long,
                  entries: List<VoipCallLogEntry>): CallDirection? =
        when (uniqueSession(packageName, startedAt, endedAt, durationMs, null, entries)
            ?.firstOrNull()?.type) {
            1 -> CallDirection.INCOMING
            2 -> CallDirection.OUTGOING
            else -> null
        }
}
