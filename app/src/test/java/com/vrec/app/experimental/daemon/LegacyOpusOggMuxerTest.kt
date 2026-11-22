package com.vrec.app.experimental.daemon

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class LegacyOpusOggMuxerTest {
    private val head = byteArrayOf(
        79, 112, 117, 115, 72, 101, 97, 100, 1, 2,
        56, 1, -128, -69, 0, 0, 0, 0, 0,
    )
    private data class Page(val flags: Int, val granule: Long, val payload: ByteArray)

    private fun pages(bytes: ByteArray): List<Page> {
        val found = mutableListOf<Page>()
        var pos = 0
        while (pos < bytes.size) {
            val header = ByteBuffer.wrap(bytes, pos, bytes.size - pos)
                .order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("OggS", String(bytes, pos, 4, Charsets.US_ASCII))
            val segments = bytes[pos + 26].toInt() and 0xff
            var payloadLength = 0
            repeat(segments) { payloadLength += bytes[pos + 27 + it].toInt() and 0xff }
            val size = 27 + segments + payloadLength
            val pageBytes = bytes.copyOfRange(pos, pos + size)
            val originalCrc = ByteBuffer.wrap(pageBytes, 22, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            java.util.Arrays.fill(pageBytes, 22, 26, 0.toByte())
            var crc = 0
            for (value in pageBytes) {
                crc = crc xor ((value.toInt() and 0xff) shl 24)
                repeat(8) {
                    crc = (crc shl 1) xor
                        (if ((crc and Int.MIN_VALUE) != 0) 0x04c11db7 else 0)
                }
            }
            assertEquals("Ogg CRC must cover complete page", originalCrc, crc)
            found += Page(
                flags = bytes[pos + 5].toInt() and 0xff,
                granule = header.getLong(6),
                payload = bytes.copyOfRange(pos + 27 + segments, pos + size),
            )
            pos += size
        }
        assertEquals(bytes.size, pos)
        return found
    }

    @Test fun writesValidHeadersAndFinalAudioPacketWithEos() {
        val output = ByteArrayOutputStream()
        LegacyOpusOggMuxer(output, head, serial = 7).use { writer ->
            writer.write(byteArrayOf(1, 2, 3))
            writer.write(byteArrayOf(4, 5))
        }
        val pages = pages(output.toByteArray())
        assertEquals(4, pages.size)
        assertEquals(2, pages[0].flags)
        assertArrayEquals(head, pages[0].payload)
        assertEquals("OpusTags", String(pages[1].payload, 0, 8, Charsets.US_ASCII))
        assertEquals(0, pages[2].flags)
        assertEquals(960L, pages[2].granule)
        assertEquals(4, pages[3].flags)
        assertEquals(1920L, pages[3].granule)
        assertArrayEquals(byteArrayOf(4, 5), pages[3].payload)
    }

    @Test fun exact255BytePacketHasTerminatingLacingByte() {
        val output = ByteArrayOutputStream()
        LegacyOpusOggMuxer(output, head, serial = 3).use {
            it.write(ByteArray(255) { 12 })
        }
        val pages = pages(output.toByteArray())
        assertEquals(3, pages.size)
        assertEquals(255, pages[2].payload.size)
        assertEquals(4, pages[2].flags)
    }

    @Test fun writingAfterFinalizeFails() {
        val writer = LegacyOpusOggMuxer(ByteArrayOutputStream(), head)
        writer.close()
        assertThrows(IllegalStateException::class.java) { writer.write(byteArrayOf(1)) }
    }
}
