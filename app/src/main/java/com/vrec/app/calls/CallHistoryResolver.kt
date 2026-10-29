package com.vrec.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.Recording
import kotlin.math.abs
import kotlin.math.max

/** Read-only, on-device call-log fallback for SIM recordings missing a caller number. */
internal data class CellularCallLogEntry(
    val number: String,
    val cachedName: String?,
    val date: Long,
    val durationSeconds: Long,
    val type: Int,
)

internal object CellularCallLogMatchPolicy {
    private const val MAX_EARLY_START_MS = 10 * 60_000L
    private const val MAX_LATE_START_MS = 60_000L
    private const val MAX_END_DRIFT_MS = 180_000L
    private const val AMBIGUOUS_MARGIN_MS = 60_000L

    /**
     * Match only a completed incoming/outgoing cellular call with a usable number.
     * Do not assign the caller of a neighbouring call based merely on the same minute.
     * Incoming call-log DATE may precede the recording by ringing time.
     */
    fun bestMatch(recording: Recording, entries: List<CellularCallLogEntry>): CellularCallLogEntry? {
        val expectedType = when (recording.callFlowDirection?.uppercase()) {
            CallDirection.INCOMING.name -> CallLog.Calls.INCOMING_TYPE
            CallDirection.OUTGOING.name -> CallLog.Calls.OUTGOING_TYPE
            else -> when (recording.direction) {
                CallDirection.INCOMING -> CallLog.Calls.INCOMING_TYPE
                CallDirection.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
                else -> return null
            }
        }
        if (recording.startedAt <= 0L) return null
        val finishedAt = recording.callEndedAt?.takeIf { it > 0L }
            ?: recording.endedAt?.takeIf { it > 0L }
            ?: (recording.startedAt + recording.durationMs)
        val candidates = entries.mapNotNull { entry ->
            if (entry.type != expectedType ||
                CallMetadataResolver.directNumber(entry.number) == null ||
                entry.date !in (recording.startedAt - MAX_EARLY_START_MS)..(recording.startedAt + MAX_LATE_START_MS)) {
                return@mapNotNull null
            }
            val estimatedEnd = entry.date + entry.durationSeconds.coerceAtLeast(0L) * 1000L
            val endDifference = abs(finishedAt - estimatedEnd)
            if (endDifference > MAX_END_DRIFT_MS) return@mapNotNull null
            val durationDifference = abs(recording.durationMs - entry.durationSeconds * 1000L)
            if (recording.durationMs > 20_000L &&
                durationDifference > max(90_000L, recording.durationMs / 3L)) return@mapNotNull null
            entry to endDifference + durationDifference / 3L
        }.sortedBy { it.second }

        val first = candidates.firstOrNull() ?: return null
        // Two plausible calls with different numbers must remain unassigned.
        if (candidates.drop(1).any { (entry, score) ->
                entry.number.filter(Char::isDigit) != first.first.number.filter(Char::isDigit) &&
                    score - first.second <= AMBIGUOUS_MARGIN_MS
            }) return null
        return first.first
    }
}

/**
 * Only reclassify a shell SIM recording when the completed Android call log
 * gives one convincing matching call. The shell can miss a RINGING broadcast
 * and then name an incoming call "_out"; timing alone near other calls is
 * not safe. This policy requires two independent signals (start/end and
 * duration), or a matching known caller, and rejects nearby alternatives.
 */
internal object CellularCallLogDirectionPolicy {
    fun bestMatch(recording: Recording, entries: List<CellularCallLogEntry>): CellularCallLogEntry? {
        if (recording.direction != CallDirection.INCOMING &&
            recording.direction != CallDirection.OUTGOING) return null
        if (recording.startedAt <= 0L || recording.durationMs < 5_000L) return null
        val finished = recording.callEndedAt?.takeIf { it > 0L }
            ?: recording.endedAt?.takeIf { it > 0L }
            ?: recording.startedAt + recording.durationMs
        val knownNumber = recording.phoneNumber?.filter(Char::isDigit)?.takeIf { it.length >= 7 }
        val candidates = entries.mapNotNull { entry ->
            if (entry.type != CallLog.Calls.INCOMING_TYPE &&
                entry.type != CallLog.Calls.OUTGOING_TYPE) return@mapNotNull null
            if (entry.durationSeconds <= 0L ||
                CallMetadataResolver.directNumber(entry.number) == null) return@mapNotNull null
            if (knownNumber != null && entry.number.filter(Char::isDigit) != knownNumber) {
                return@mapNotNull null
            }
            val startOffset = recording.startedAt - entry.date
            if (startOffset !in -15_000L..180_000L) return@mapNotNull null
            val durationDifference =
                abs(recording.durationMs - entry.durationSeconds * 1000L)
            if (durationDifference > 45_000L) return@mapNotNull null
            val endDifference = abs(finished -
                (entry.date + entry.durationSeconds * 1000L))
            if (endDifference > 40_000L) return@mapNotNull null
            val score = endDifference + durationDifference + abs(startOffset) / 3L
            entry to score
        }.sortedBy { it.second }
        val closest = candidates.firstOrNull() ?: return null
        // Even two calls of the same caller can overlap in call waiting.
        if (candidates.drop(1).any { (_, score) ->
                score - closest.second <= 60_000L
            }) return null
        return closest.first
    }
}

internal object CallHistoryResolver {
    fun recoverDirection(context: Context, recording: Recording): CallDirection? {
        if (recording.direction != CallDirection.INCOMING &&
            recording.direction != CallDirection.OUTGOING) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED) return null
        val entries = readEntries(context, recording.startedAt, null) ?: return null
        val best = CellularCallLogDirectionPolicy.bestMatch(recording, entries) ?: return null
        return when (best.type) {
            CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
            CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
            else -> null
        }
    }

    fun recover(context: Context, recording: Recording): CallerMetadata? {
        if (recording.direction != CallDirection.INCOMING &&
            recording.direction != CallDirection.OUTGOING) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val calls = readEntries(context, recording.startedAt,
            when (recording.callFlowDirection?.uppercase()) {
                CallDirection.INCOMING.name -> CallLog.Calls.INCOMING_TYPE
                CallDirection.OUTGOING.name -> CallLog.Calls.OUTGOING_TYPE
                else -> if (recording.direction == CallDirection.INCOMING)
                    CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE
            }) ?: return null
        val selected = CellularCallLogMatchPolicy.bestMatch(recording, calls) ?: return null
        val fromContacts = CallInfo.describe(context, selected.number)
            ?.takeUnless { it == selected.number }
        return CallerMetadata(
            name = fromContacts ?: CallMetadataResolver.cleanName(selected.cachedName),
            number = selected.number,
            source = "call-log",
        )
    }

    private fun readEntries(context: Context, startedAt: Long, type: Int?): List<CellularCallLogEntry>? {
        if (startedAt <= 0L) return null
        return runCatching {
            val columns = arrayOf(
                CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
                CallLog.Calls.DURATION, CallLog.Calls.TYPE,
            )
            val selection = buildString {
                append("${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?")
                if (type != null) append(" AND ${CallLog.Calls.TYPE} = ?")
                else append(" AND ${CallLog.Calls.TYPE} IN (?, ?)")
            }
            val args = listOf(
                (startedAt - 10 * 60_000L).toString(),
                (startedAt + 60_000L).toString(),
            ) + if (type == null) listOf(
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.OUTGOING_TYPE.toString(),
            ) else listOf(type.toString())
            val entries = mutableListOf<CellularCallLogEntry>()
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, columns,
                selection, args.toTypedArray(), "${CallLog.Calls.DATE} DESC")
                ?.use { cursor ->
                    var rows = 0
                    while (cursor.moveToNext() && rows++ < 100) {
                        entries += CellularCallLogEntry(
                            cursor.getString(0).orEmpty(), cursor.getString(1),
                            cursor.getLong(2), cursor.getLong(3), cursor.getInt(4))
                    }
                }
            entries
        }.getOrNull()
    }
}
