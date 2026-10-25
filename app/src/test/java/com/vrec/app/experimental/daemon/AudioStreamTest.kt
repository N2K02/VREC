package com.vrec.app.experimental.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class AudioStreamTest {
    private class FakeSocket(private val bytes: ByteArray) : Socket() {
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun framed(packetData: ByteArray = byteArrayOf(1, 2, 3)): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeBytes("opus")
                out.writeLong(123456L)
                out.writeInt(packetData.size)
                out.write(packetData)
            }
        }.toByteArray()

    @Test fun readsCompletePacketAndStopsOnlyAtExactBoundary() {
        AudioStream(FakeSocket(framed())).use { stream ->
            assertEquals("opus", stream.codecId)
            val packet = stream.readPacket()!!
            assertEquals(123456L, packet.pts)
            assertArrayEquals(byteArrayOf(1, 2, 3), packet.data)
            assertNull(stream.readPacket())
        }
    }

    @Test fun cleanEndImmediatelyAfterCodecIsAllowed() {
        AudioStream(FakeSocket("opus".toByteArray())).use {
            assertNull(it.readPacket())
        }
    }

    @Test fun partiallyWrittenFrameHeaderMustFailImport() {
        val truncated = framed().copyOfRange(0, 8) // codec + partial pts
        AudioStream(FakeSocket(truncated)).use {
            assertThrows(EOFException::class.java) { it.readPacket() }
        }
    }

    @Test fun partiallyWrittenPayloadMustFailImport() {
        val truncated = framed().copyOf(framed().size - 1)
        AudioStream(FakeSocket(truncated)).use {
            assertThrows(EOFException::class.java) { it.readPacket() }
        }
    }

    @Test fun implausiblePayloadIsRejectedBeforeAllocatingMemory() {
        val raw = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeBytes("opus")
                out.writeLong(1L)
                out.writeInt(1 shl 25)
            }
        }.toByteArray()
        AudioStream(FakeSocket(raw)).use {
            assertThrows(IllegalStateException::class.java) { it.readPacket() }
        }
    }
}
