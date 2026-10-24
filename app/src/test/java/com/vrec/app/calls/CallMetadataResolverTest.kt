package com.vrec.app.calls

import org.junit.Assert.*
import org.junit.Test

class CallMetadataResolverTest {
    @Test fun rejectsPlaceholderAndNotificationNarrative() {
        assertNull(CallMetadataResolver.cleanName("Incoming call"))
        assertNull(CallMetadataResolver.cleanName("WhatsApp"))
        assertNull(CallMetadataResolver.cleanName("Call in progress"))
        assertNull(CallMetadataResolver.cleanName("From: John"))
    }

    @Test fun acceptsActualNamesWithoutTreatingThemAsPhoneNumbers() {
        assertEquals("John Smith", CallMetadataResolver.cleanName("  John   Smith  "))
        assertNull(CallMetadataResolver.cleanName("+230 5123 4567"))
    }

    @Test fun numbersRequireDigitsAndExplicitPhoneShape() {
        assertEquals("+230 5123 4567", CallMetadataResolver.directNumber("tel:+230 5123 4567"))
        assertNull(CallMetadataResolver.directNumber("John 12345678"))
        assertNull(CallMetadataResolver.directNumber("123"))
    }
}
