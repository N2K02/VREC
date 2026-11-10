package com.vrec.app.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog

/** Looks only at cellular call timestamps, not numbers, contacts or call contents. */
internal object MissedCallWatchAudit {
    /** A call log entry more than 2 seconds newer than the last observed off-hook
     * suggests the daemon missed it. No call before the watcher started is compared. */
    fun missedSince(since: Long, lastOffHook: Long, callStarts: List<Long>): Boolean =
        since > 0 && callStarts.any { it >= since + 2_000L && it > lastOffHook + 2_000L }

    fun recentCellularCalls(context: Context, since: Long): List<Long>? {
        if (context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED)
            return null
        if (since <= 0L) return null
        return runCatching {
            val dates = mutableListOf<Long>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.TYPE),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} IN (?, ?)",
                arrayOf(since.toString(), CallLog.Calls.INCOMING_TYPE.toString(),
                    CallLog.Calls.OUTGOING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (dates.size >= 100) return null // incomplete sample cannot prove coverage
                    dates.add(cursor.getLong(0))
                }
            } ?: return null
            dates
        }.getOrNull()
    }
}
