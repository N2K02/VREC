package com.vrec.app.recording

/** Centralizes destructive cleanup decisions; saved recordings default to unlimited retention. */
internal object RecordingRetentionPolicy {
    const val DAY_MS = 86_400_000L
    fun savedEligible(startedAt: Long, favorite: Boolean, days: Int, now: Long): Boolean =
        days in setOf(30, 90, 180, 365) && !favorite && startedAt > 0L &&
            startedAt < now - days * DAY_MS

    fun trashExpired(deletedAt: Long, days: Int, now: Long): Boolean =
        deletedAt > 0L && deletedAt < now - days.coerceIn(1, 90) * DAY_MS
}
