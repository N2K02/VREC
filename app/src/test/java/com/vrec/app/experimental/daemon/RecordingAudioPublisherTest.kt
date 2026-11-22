package com.vrec.app.experimental.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RecordingAudioPublisherTest {
    private val header = byteArrayOf(
        79, 112, 117, 115, 72, 101, 97, 100, 1, 2,
        56, 1, -128, -69, 0, 0, 0, 0, 0,
    )

    private fun ogg(sample: Byte): ByteArray = ByteArrayOutputStream().also { output ->
        LegacyOpusOggMuxer(output, header, serial = 77).use {
            it.write(byteArrayOf(8, sample))
        }
    }.toByteArray()

    @Test fun invalidReplacementCannotDeleteExistingRecording() {
        val dir = createTempDir(prefix = "vrec-preserve-")
        try {
            val dest = File(dir, "recording.ogg")
            val temp = File(dir, "recording.ogg.tmp")
            val original = ogg(1)
            dest.writeBytes(original)
            temp.writeBytes(byteArrayOf(1, 2, 3))
            assertThrows(IllegalStateException::class.java) {
                RecordingAudioPublisher.publish(temp, dest)
            }
            assertArrayEquals(original, dest.readBytes())
            assertFalse(File(dir, "recording.ogg.previous").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun verifiedReplacementPublishesWithoutLeavingBackup() {
        val dir = createTempDir(prefix = "vrec-publish-")
        try {
            val dest = File(dir, "recording.ogg")
            val temp = File(dir, "recording.ogg.tmp")
            val replacement = ogg(2)
            dest.writeBytes(ogg(1))
            temp.writeBytes(replacement)
            RecordingAudioPublisher.publish(temp, dest)
            assertArrayEquals(replacement, dest.readBytes())
            assertFalse(temp.exists())
            assertFalse(File(dir, "recording.ogg.previous").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun restartRestoresOriginalWhenPublicationWasInterrupted() {
        val dir = createTempDir(prefix = "vrec-recovery-")
        try {
            val dest = File(dir, "recording.ogg")
            val previous = File(dir, "recording.ogg.previous")
            val original = ogg(1)
            previous.writeBytes(original)
            // Simulates a crash after the original was moved to .previous.
            RecordingAudioPublisher.recover(dest)
            assertArrayEquals(original, dest.readBytes())
            assertFalse(previous.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
