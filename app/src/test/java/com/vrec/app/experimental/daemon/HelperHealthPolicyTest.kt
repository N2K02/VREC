package com.vrec.app.experimental.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperHealthPolicyTest {
    @Test fun noAuthenticatedHelperRequiresAdb() {
        assertEquals(AdbPrivilegedApiHelper.State.NEEDS_ADB,
            HelperHealthPolicy.nextState(false, false))
        assertEquals(AdbPrivilegedApiHelper.State.NEEDS_ADB,
            HelperHealthPolicy.nextState(false, true))
    }

    @Test fun pongWithoutCurrentModeHandshakeCannotArmRecorder() {
        assertEquals(AdbPrivilegedApiHelper.State.ERROR,
            HelperHealthPolicy.nextState(true, false))
    }

    @Test fun reverifiedHelperCanRecoverFromPreviousError() {
        assertEquals(AdbPrivilegedApiHelper.State.RUNNING,
            HelperHealthPolicy.nextState(true, true))
    }
}
