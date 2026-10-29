package com.vrec.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityLabelContractTest {
    private fun read(path: String): String =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $path")

    @Test fun serviceLabelIsVrecCallRecordingHelper() {
        val strings = read("app/src/main/res/values/strings.xml")
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(strings.contains("<string name=\"accessibility_label\">Vrec call recording helper</string>"))
        assertTrue(manifest.contains("android:label=\"@string/accessibility_label\""))
    }

    @Test fun wifiPairingPermissionsAreNotDeclared() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(!manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(!manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }
}
