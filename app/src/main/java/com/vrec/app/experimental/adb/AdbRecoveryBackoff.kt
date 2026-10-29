package com.vrec.app.experimental.adb

/**
 * Exponential backoff for automatic recovery: 1 min, 2, 4, 8, 16, capped at
 * 30 min. A success resets it. Keeps background recovery from re-enabling
 * Wireless debugging or reconnecting ADB over and over on a phone where it
 * isn't working.
 */
class AdbRecoveryBackoff(
    private val baseMs: Long = 60_000L,
    private val maxMs: Long = 30 * 60_000L,
) {
    var failures = 0
        private set
    private var nextAllowedAt = 0L

    fun allowed(nowMs: Long): Boolean = nowMs >= nextAllowedAt

    fun delayFor(failures: Int): Long =
        if (failures <= 0) 0L
        else (baseMs shl (failures - 1).coerceAtMost(20)).coerceAtMost(maxMs)

    fun onFailure(nowMs: Long) {
        if (failures < Int.MAX_VALUE) failures++
        val delay = delayFor(failures)
        nextAllowedAt = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay
    }

    fun onSuccess() {
        failures = 0
        nextAllowedAt = 0L
    }
}
