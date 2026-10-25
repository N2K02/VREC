package com.vrec.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep VREC's recording shortcuts in the persistent bottom bar, not Home's Explore area. */
class RecordingsBottomNavigationContractTest {
    private fun mainSource(): String {
        val relative = "com/vrec/app/MainActivity.kt"
        return listOf(
            File("app/src/main/java", relative),
            File("src/main/java", relative),
        ).firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
    }

    @Test fun secondBottomTabOpensRecordingsAndHasCorrectSelection() {
        val source = mainSource()
        val nav = source.substringAfter("NavigationBar(").substringBefore("onClick = {")
        val home = nav.indexOf("Triple(Screen.HOME, \"Home\"")
        val recordings = nav.indexOf("Triple(Screen.RECORDINGS, \"Recordings\"")
        val settings = nav.indexOf("Triple(Screen.SETTINGS, \"Settings\"")
        assertTrue("Home, Recordings, Settings must appear in that order", home >= 0 && home < recordings && recordings < settings)
        assertTrue(source.indexOf("label = { Text(\"Quit\") }") > source.indexOf("Triple(Screen.SETTINGS, \"Settings\""))
        assertTrue(source.contains("Screen.RECORDINGS -> screen == Screen.RECORDINGS"))
        assertTrue(source.contains("onClick = { navigateTo(destination) }"))
        assertTrue(source.contains("Screen.RECORDINGS -> androidx.compose.foundation.layout.Box("))
        assertTrue(source.contains("RecordingsList()"))
    }

    @Test fun homeRemovesExploreShortcutsButKeepsRecordingAndSetup() {
        val source = mainSource()
        val home = source.substringAfter("private fun HomeScreen(").substringBefore("private fun RecordingMasterCard(")
        assertFalse(home.contains("Text(\"Explore\""))
        assertFalse(home.contains("NavigationCard("))
        assertFalse(source.contains("private fun NavigationCard("))
        assertTrue(home.contains("RecordingMasterCard("))
        assertTrue(home.contains("Text(\"Finish setup\")"))
        // Home now uses the one-tap, in-app setup card rather than a raw TCP button.
        assertTrue(home.contains("LocalAdbHomeCard(onOpenDetailedSetup = onOpenExperimental)"))
    }
}
