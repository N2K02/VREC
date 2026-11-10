package com.vrec.app.calls

import org.junit.Assert.*
import org.junit.Test

class ContactRecordingRulesTest {
    @Test fun exactNumberMatches() {
        assertTrue(ContactRecordingRules.matches("+230 5123 4567", "+23051234567, +33123456789"))
    }
    @Test fun unknownAndShortNumbersNeverMatch() {
        assertFalse(ContactRecordingRules.matches(null, "+23051234567"))
        assertFalse(ContactRecordingRules.matches("1234", "1234"))
    }
    @Test fun unrelatedCallerIsRejected() {
        assertFalse(ContactRecordingRules.matches("+23051234567", "+23059876543"))
    }
    @Test fun differentCountryPrefixCanMatchLongSuffix() {
        assertTrue(ContactRecordingRules.matches("+442071234567", "02071234567"))
    }
}
