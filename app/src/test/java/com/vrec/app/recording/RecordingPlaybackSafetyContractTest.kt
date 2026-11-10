package com.vrec.app.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards playback lifecycle and privacy without requiring Android media hardware. */
class RecordingPlaybackSafetyContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun foregroundPlaybackIsDeclaredAndRequiresAudioFocus() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        val playback = read("app/src/main/java/com/vrec/app/recording/RecordingPlaybackService.kt")
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"mediaPlayback\""))
        assertTrue(playback.contains("audioManager.requestAudioFocus(focusRequest)"))
        assertTrue(playback.contains("AudioManager.AUDIOFOCUS_LOSS"))
        assertTrue(playback.contains("audioManager.abandonAudioFocusRequest(focusRequest)"))
    }

    @Test fun notificationDoesNotExposeRecordingFilenameOnLockscreen() {
        val playback = read("app/src/main/java/com/vrec/app/recording/RecordingPlaybackService.kt")
        assertTrue(playback.contains(".setVisibility(Notification.VISIBILITY_PRIVATE)"))
        assertTrue(playback.contains(".setPublicVersion(Notification.Builder(this, CHANNEL)"))
        assertTrue(playback.contains(".setContentText(\"Recording playback\")"))
        assertTrue(playback.contains(".setContentText(playbackDetails)"))
    }

    @Test fun disposingScreenDoesNotReleaseSharedPlayer() {
        val list = read("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        val playback = read("app/src/main/java/com/vrec/app/recording/RecordingPlaybackService.kt")
        assertTrue(playback.contains("player.setOnCompletionListener { stopPlayback() }"))
        assertTrue(playback.contains("override fun onDestroy()"))
        assertTrue(list.contains("Playback notification could not be started."))
        assertTrue(!list.contains("onDispose { runCatching { player.release() } }"))
    }
}
