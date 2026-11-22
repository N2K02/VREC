package com.vrec.app.experimental.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
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

    private val validMeta: (File) -> Boolean = { audio ->
        File(audio.parentFile, audio.name + ".json").let { it.isFile && it.readText().startsWith("v3:") }
    }

    private fun sidecar(audio: File) = File(audio.parentFile, audio.name + ".json")

    private fun testFiles(block: (File, File) -> Unit) {
        val dir = createTempDirectory("vrec-transaction-").toFile()
        try {
            block(File(dir, "recording.ogg"), File(dir, "recording.ogg.tmp"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun invalidReplacementPreservesPreviousAudioAndMetadata() = testFiles { dest, temp ->
        val old = ogg(1)
        dest.writeBytes(old)
        sidecar(dest).writeText("v3:old")
        temp.writeBytes(byteArrayOf(1, 2, 3))
        sidecar(temp).writeText("v3:new")
        assertThrows(IllegalStateException::class.java) {
            RecordingAudioPublisher.publish(temp, dest, validMeta)
        }
        assertArrayEquals(old, dest.readBytes())
        assertEquals("v3:old", sidecar(dest).readText())
    }

    @Test fun invalidMetadataDoesNotReplaceValidAudio() = testFiles { dest, temp ->
        val old = ogg(1)
        dest.writeBytes(old)
        sidecar(dest).writeText("v3:old")
        temp.writeBytes(ogg(2))
        sidecar(temp).writeText("invalid")
        assertThrows(IllegalStateException::class.java) {
            RecordingAudioPublisher.publish(temp, dest, validMeta)
        }
        assertArrayEquals(old, dest.readBytes())
        assertEquals("v3:old", sidecar(dest).readText())
    }

    @Test fun verifiedReplacementPublishesMatchingPairWithoutBackup() = testFiles { dest, temp ->
        val replacement = ogg(2)
        dest.writeBytes(ogg(1))
        sidecar(dest).writeText("v3:old")
        temp.writeBytes(replacement)
        sidecar(temp).writeText("v3:new")
        RecordingAudioPublisher.publish(temp, dest, validMeta)
        assertArrayEquals(replacement, dest.readBytes())
        assertEquals("v3:new", sidecar(dest).readText())
        assertFalse(temp.exists())
        assertFalse(sidecar(temp).exists())
        assertFalse(File(dest.path + ".previous").exists())
        assertFalse(File(sidecar(dest).path + ".previous").exists())
        assertFalse(File(dest.path + ".committed").exists())
    }

    @Test fun crashAfterAudioMoveRollsBackBothSides() = testFiles { dest, temp ->
        val old = ogg(1)
        val replacement = ogg(2)
        File(dest.path + ".previous").writeBytes(old)
        File(sidecar(dest).path + ".previous").writeText("v3:old")
        dest.writeBytes(replacement)
        // No new metadata yet: crash between audio and sidecar rename.
        RecordingAudioPublisher.recover(dest, validMeta)
        assertArrayEquals(old, dest.readBytes())
        assertEquals("v3:old", sidecar(dest).readText())
        assertFalse(File(dest.path + ".previous").exists())
        assertFalse(File(sidecar(dest).path + ".previous").exists())
    }

    @Test fun crashAfterBothMovesWithoutCommitRestoresOldPair() = testFiles { dest, temp ->
        val old = ogg(1)
        File(dest.path + ".previous").writeBytes(old)
        File(sidecar(dest).path + ".previous").writeText("v3:old")
        dest.writeBytes(ogg(2))
        sidecar(dest).writeText("v3:new")
        RecordingAudioPublisher.recover(dest, validMeta)
        assertArrayEquals(old, dest.readBytes())
        assertEquals("v3:old", sidecar(dest).readText())
    }

    @Test fun committedPairSurvivesInterruptedBackupCleanup() = testFiles { dest, temp ->
        val newAudio = ogg(2)
        dest.writeBytes(newAudio)
        sidecar(dest).writeText("v3:new")
        File(dest.path + ".previous").writeBytes(ogg(1))
        File(sidecar(dest).path + ".previous").writeText("v3:old")
        File(dest.path + ".committed").writeText("1")
        RecordingAudioPublisher.recover(dest, validMeta)
        assertArrayEquals(newAudio, dest.readBytes())
        assertEquals("v3:new", sidecar(dest).readText())
        assertFalse(File(dest.path + ".previous").exists())
        assertFalse(File(sidecar(dest).path + ".previous").exists())
        assertFalse(File(dest.path + ".committed").exists())
    }
}
