package com.vrec.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerPresentationPolicyTest {
    @Test fun explicitTelecomRestrictionLabelsCellularCallerPrivate() {
        assertEquals("Private number",
            CallerPresentationPolicy.resolvedName(true, true, null, null))
    }

    @Test fun absentCallerNumberAloneNeverMeansPrivate() {
        assertNull(CallerPresentationPolicy.resolvedName(true, false, null, null))
        assertNull(CallerPresentationPolicy.resolvedName(false, true, null, null))
    }

    @Test fun verifiedNameOrNumberWinsOverPrivateFallback() {
        assertEquals("Saved contact",
            CallerPresentationPolicy.resolvedName(true, true, "Saved contact", null))
        assertNull(CallerPresentationPolicy.resolvedName(true, true, null, "+23051234567"))
    }
}
