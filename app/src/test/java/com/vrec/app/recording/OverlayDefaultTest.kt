package com.vrec.app.recording

import com.vrec.app.Prefs
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayDefaultTest {
    @Test fun enabledForNewInstallUnlessUserExplicitlyDisables() {
        assertTrue(Prefs.SHOW_OVERLAY_DEFAULT)
    }
}
