package com.vrec.app.recording

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingTrashFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun movesAndRestoresAudioAndMetadata() {
        val source = temp.newFile("call.ogg").apply { writeText("audio") }
        val metadata = temp.newFile("call.ogg.json").apply { writeText("notes") }
        val trash = File(temp.root, ".trash/call.ogg")
        assertTrue(RecordingTrashFiles.move(source, trash))
        assertFalse(source.exists())
        assertEquals("notes", File(trash.parentFile, "call.ogg.json").readText())
        assertTrue(RecordingTrashFiles.move(trash, source))
        assertEquals("audio", source.readText())
        assertEquals("notes", metadata.readText())
    }

    @Test fun neverOverwritesExistingRecording() {
        val source = temp.newFile("call.ogg")
        val trash = temp.newFolder("trash")
        File(trash, "call.ogg").writeText("existing")
        assertFalse(RecordingTrashFiles.move(source, File(trash, "call.ogg")))
        assertTrue(source.exists())
    }

    @Test fun refusesMissingAudio() {
        assertFalse(RecordingTrashFiles.move(File(temp.root, "missing.ogg"),
            File(temp.root, "trash/missing.ogg")))
    }

    @Test fun metadataCollisionRollsBackAudioMove() {
        val source = temp.newFile("call.ogg")
        temp.newFile("call.ogg.json")
        val trash = temp.newFolder("trash")
        File(trash, "call.ogg.json").writeText("conflict")
        assertFalse(RecordingTrashFiles.move(source, File(trash, "call.ogg")))
        assertTrue(source.exists())
    }
}
