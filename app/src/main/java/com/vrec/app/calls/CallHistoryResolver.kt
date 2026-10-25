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
        val expectedType = when (recording.direction) {
            CallDirection.INCOMING -> CallLog.Calls.INCOMING_TYPE
            CallDirection.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
            else -> return null
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

internal object CallHistoryResolver {
    fun recover(context: Context, recording: Recording): CallerMetadata? {
        if (recording.direction != CallDirection.INCOMING &&
            recording.direction != CallDirection.OUTGOING) return null
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return null
        val callType = if (recording.direction == CallDirection.INCOMING)
            CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE
        val calls = mutableListOf<CellularCallLogEntry>()
        runCatching {
            val cols = arrayOf(
                CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE,
                CallLog.Calls.DURATION, CallLog.Calls.TYPE,
            )
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                cols,
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ? AND ${CallLog.Calls.TYPE} = ?",
                arrayOf(
                    (recording.startedAt - 10 * 60_000L).toString(),
                    (recording.startedAt + 60_000L).toString(),
                    callType.toString(),
                ),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                var rows = 0
                while (cursor.moveToNext() && rows++ < 100) {
                    calls += CellularCallLogEntry(
                        number = cursor.getString(0).orEmpty(),
                        cachedName = cursor.getString(1),
                        date = cursor.getLong(2),
                        durationSeconds = cursor.getLong(3),
                        type = cursor.getInt(4),
                    )
                }
            }
        }.onFailure {
            // Call-log access can be unavailable even in a personally installed APK.
            return null
        }
        val selected = CellularCallLogMatchPolicy.bestMatch(recording, calls) ?: return null
        val fromContacts = CallInfo.describe(context, selected.number)
            ?.takeUnless { it == selected.number }
        return CallerMetadata(
            name = fromContacts ?: CallMetadataResolver.cleanName(selected.cachedName),
            number = selected.number,
            source = "call-log",
        )
    }
}
