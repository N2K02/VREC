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

    @Test fun recordingListShowsDurationWithoutExpandingCards() {
        val ui = read("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        val row = ui.substringAfter("private fun RecordingRow(").substringBefore("private fun ")
        assertTrue(row.contains("dateFmt.format(Date(rec.startedAt))"))
        assertTrue(row.contains("if (rec.durationMs > 0L)"))
        assertTrue(row.contains("formatDuration(rec.durationMs)"))
        assertTrue(row.contains("Text(displayedContact"))
    }

    @Test fun waveformsSampleLongFilesInsteadOfFullyDecodingThem() {
        val decoder = read("app/src/main/java/com/vrec/app/recording/RecordingWaveform.kt")
        val row = read("app/src/main/java/com/vrec/app/ui/RecordingsList.kt")
        assertTrue(decoder.contains("extractor.seekTo(positionUs"))
        assertTrue(decoder.contains("decoder.flush()"))
        assertTrue(decoder.contains("LruCache<String, FloatArray>"))
        assertTrue(decoder.contains("DEADLINE_MS"))
        assertTrue(row.contains("withContext(Dispatchers.IO)"))
        assertTrue(row.contains("Loading waveform…"))
        assertTrue(row.contains("detectTapGestures { offset ->"))
        assertTrue(row.contains("StrokeCap.Round"))
        assertTrue(row.contains("kotlin.math.sqrt(raw.coerceIn(0f, 1f))"))
    }
}
