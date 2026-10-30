package com.vrec.app.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM test of the Android Settings-only pairing-code reader. */
class PairingCodeUiPolicyTest {
    @Test fun onlyKnownAndroidSettingsPackagesAreReadable() {
        assertTrue(PairingCodeUiPolicy.isSettingsPackage("com.android.settings"))
        assertTrue(PairingCodeUiPolicy.isSettingsPackage("com.google.android.settings"))
        assertTrue(PairingCodeUiPolicy.isSettingsPackage("com.android.tv.settings"))
        assertTrue(PairingCodeUiPolicy.isSettingsPackage("com.samsung.android.settings"))
        assertFalse(PairingCodeUiPolicy.isSettingsPackage("com.example.settings"))
        assertFalse(PairingCodeUiPolicy.isSettingsPackage("com.whatsapp"))
        assertFalse(PairingCodeUiPolicy.isSettingsPackage(null))
    }

    @Test fun findsCodeInDedicatedAndroidSettingsTextNode() {
        assertEquals("012345", PairingCodeUiPolicy.extractCode(
            listOf("Pair with device", "Wi-Fi pairing code", "012345", "192.168.1.20:35644")))
    }

    @Test fun readsInlineCodeOnSupportedOemSettings() {
        assertEquals("987654", PairingCodeUiPolicy.extractCode(
            listOf("Pair with device", "Wi-Fi pairing code: 987654")))
    }

    @Test fun ignoresRandomNumbersOutsidePairingDialog() {
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Wireless debugging", "123456")))
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Network preferences", "123456")))
    }

    @Test fun rejectsNonSixDigitCodesAndIpAddresses() {
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Pair with device", "12345")))
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Pair with device", "1234567")))
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Pair with device", "192.168.1.100:12345")))
        assertNull(PairingCodeUiPolicy.extractCode(listOf("Wi-Fi pairing code: 1234567")))
    }

    @Test fun prefersDedicatedCodeOverNumbersInOtherLabels() {
        assertEquals("654321", PairingCodeUiPolicy.extractCode(
            listOf("Pair with device", "192.168.1.20:36577", "654321", "Phone 987654")))
    }
}
