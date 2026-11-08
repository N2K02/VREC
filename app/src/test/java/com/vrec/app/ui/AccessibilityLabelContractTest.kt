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

    @Test fun wifiPairingPermissionsAreNotDeclared() {\n        val manifest = read("app/src/main/AndroidManifest.xml")
        val activity = read("app/src/main/java/com/vrec/app/MainActivity.kt")
        assertTrue(manifest.contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(activity.contains("AdbNetworkAccess.requiresPermission(this)"))
        assertTrue(activity.contains("AdbNetworkAccess.granted(this)"))
        assertTrue(!manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))\n    }
}
