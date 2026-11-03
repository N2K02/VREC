package com.vrec.app.calls

import kotlin.math.abs

internal data class VoipCallLogEntry(
    val packageName: String?, val accountId: String?, val number: String?,
    val date: Long, val durationSeconds: Long, val type: Int,
)

/** No contact-name matching: accept numbers only from one verified app call-log session. */
internal object VoipCallLogPolicy {
    fun numbers(packageName: String, startedAt: Long, endedAt: Long, durationMs: Long,
                flow: String?, entries: List<VoipCallLogEntry>): List<String> {
        if (packageName.isBlank() || startedAt <= 0L || durationMs < 5_000L) return emptyList()
        val expectedType = when (flow) { "INCOMING" -> 1; "OUTGOING" -> 2; else -> null }
        val groups = entries.filter { row ->
            row.packageName == packageName && row.type in 1..2 &&
                (expectedType == null || row.type == expectedType) && row.durationSeconds > 0 &&
                startedAt - row.date in -15_000L..180_000L &&
                abs(durationMs - row.durationSeconds * 1000) <= 45_000L &&
                abs(endedAt - (row.date + row.durationSeconds * 1000)) <= 40_000L
        }.groupBy { listOf(it.accountId, it.date, it.durationSeconds, it.type) }
        // Identical session fields can describe per-participant rows. Distinct calls stay ambiguous.
        val rows = groups.values.singleOrNull() ?: return emptyList()
        return VoipIdentityPolicy.numbers(rows.mapNotNull { it.number })
    }
}
