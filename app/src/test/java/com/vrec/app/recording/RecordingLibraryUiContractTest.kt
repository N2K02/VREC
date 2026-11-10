package com.vrec.app.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensure the compact library and large-recording waveform preview remain usable. */
class RecordingLibraryUiContractTest {
    private fun read(path: String): String = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun libraryUsesCompactSummaryChips() {
        val ui = read("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        assertTrue(ui.contains("SuggestionChip("))
        assertTrue(ui.contains("Deleted \${trashedRecordings.size}"))
        assertTrue(ui.contains("formatSize(totalBytes)"))
        assertFalse(ui.contains("Text(\"Saved recordings\", style = MaterialTheme.typography.titleLarge"))
    }

    @Test fun waveformsSampleLongFilesInsteadOfFullyDecodingThem() {
        val decoder = read("app/src/main/java/com/vrec/app/recording/RecordingWaveform.kt")
        val row = read("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        assertTrue(decoder.contains("extractor.seekTo(positionUs"))
        assertTrue(decoder.contains("decoder.flush()"))
        assertTrue(decoder.contains("LruCache<String, FloatArray>"))
        assertTrue(decoder.contains("DEADLINE_MS"))
        assertTrue(row.contains("withContext(Dispatchers.IO)"))
        assertTrue(row.contains("Generating waveform…"))
        assertTrue(row.contains("waveform?.let { peaks ->"))
    }
}
