package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbBadgePolicyTest {
    @Test fun eachStateRequiresCorrectEvidence() {
        val empty = AdbBadgePolicy.Signals(false, false, false)
        assertEquals(AdbBadgePolicy.State.DISCONNECTED, AdbBadgePolicy.resolve(empty))
        assertEquals(AdbBadgePolicy.State.FAILED, AdbBadgePolicy.resolve(empty.copy(failed = true)))
        assertEquals(AdbBadgePolicy.State.IN_PROGRESS, AdbBadgePolicy.resolve(empty.copy(busy = true)))
        assertEquals(AdbBadgePolicy.State.SUCCESS,
            AdbBadgePolicy.resolve(empty.copy(verifiedUid2000 = true)))
        assertEquals(AdbBadgePolicy.State.IN_PROGRESS,
            AdbBadgePolicy.resolve(empty.copy(busy = true, verifiedUid2000 = true)))
    }
}
