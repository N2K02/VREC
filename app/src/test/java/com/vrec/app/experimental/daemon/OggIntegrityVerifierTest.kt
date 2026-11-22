package com.vrec.app.experimental.daemon

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class OggIntegrityVerifierTest {
    private val header = byteArrayOf(
        79, 112, 117, 115, 72, 101, 97, 100, 1, 2,
        56, 1, -128, -69, 0, 0, 0, 0, 0,
    )

    private fun validOgg(): ByteArray = ByteArrayOutputStream().also { bytes ->
        LegacyOpusOggMuxer(bytes, header, serial = 77).use { writer ->
            writer.write(byteArrayOf(8, 5, 6))
            writer.write(byteArrayOf(8, 7, 9))
        }
    }.toByteArray()

    private fun verify(bytes: ByteArray): Boolean {
        val file = File.createTempFile("vrec-ogg-test-", ".ogg")
        return try {
            file.writeBytes(bytes)
            OggIntegrityVerifier.valid(file)
        } finally {
            file.delete()
        }
    }

    @Test fun completeLegacyOggWithAudioAndEosPasses() {
        assertTrue(verify(validOgg()))
    }

    @Test fun truncatedOggAndCorruptPagePayloadAreRejected() {
        val valid = validOgg()
        assertFalse(verify(valid.copyOf(valid.size - 1)))
        val corrupted = valid.clone()
        corrupted[corrupted.size - 5] = (corrupted[corrupted.size - 5].toInt() xor 1).toByte()
        assertFalse(verify(corrupted))
    }

    @Test fun trailingGarbageIsRejectedEvenAfterAnEosPage() {
        assertFalse(verify(validOgg() + byteArrayOf(1, 2, 3)))
    }

    @Test fun audioWithoutFinalEosIsNotAccepted() {
        val valid = validOgg()
        // The last page's end-of-stream flag is part of the CRC, so this is
        // rejected even when the data bytes themselves look plausible.
        val last = valid.indexOfSlice("OggS".toByteArray(Charsets.US_ASCII), 4)
        assertTrue(last > 0)
        assertFalse(verify(valid.copyOf(last)))
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray, start: Int): Int {
        for (i in start..(size - needle.size)) {
            if (needle.indices.all { j -> this[i + j] == needle[j] }) return i
        }
        return -1
    }
}
