package com.vrec.app.experimental.adb

/** UID badge must never treat an open port or debugging toggle as successful ADB. */
internal object AdbBadgePolicy {
    enum class State { SUCCESS, FAILED, IN_PROGRESS, DISCONNECTED }
    data class Signals(val busy: Boolean, val verifiedUid2000: Boolean, val failed: Boolean)
    fun resolve(s: Signals): State = when {
        s.busy -> State.IN_PROGRESS
        s.verifiedUid2000 -> State.SUCCESS
        s.failed -> State.FAILED
        else -> State.DISCONNECTED
    }
}
