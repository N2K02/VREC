package com.vrec.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMetadataResolverPrivateTest {
    @Test fun withheldNumberHasExplicitPrivateLabel() {
        assertTrue(CallMetadataResolver.privateCallerLabel("Private number"))
        assertTrue(CallMetadataResolver.privateCallerLabel("No caller ID"))
        assertTrue(CallMetadataResolver.privateCallerLabel("Withheld"))
        assertTrue(CallMetadataResolver.privateCallerLabel("Anonymous"))
        assertFalse(CallMetadataResolver.privateCallerLabel("Unknown number"))
    }

    @Test fun formattingMarksDoNotHidePhoneNumbers() {
        assertEquals("+230 5123 4567",
            CallMetadataResolver.directNumber("\u202A+230\u00A05123 4567\u202C"))
    }

    @Test fun formattingMarksDoNotHideContactNames() {
        assertEquals("Alice Smith", CallMetadataResolver.cleanName("\u2066Alice Smith\u2069"))
    }

    @Test fun genericCallLabelsAreNotCallerNames() {
        assertNull(CallMetadataResolver.cleanName("Incoming call"))
        assertNull(CallMetadataResolver.cleanName("Private number"))
    }
}
