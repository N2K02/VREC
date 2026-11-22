package com.vrec.app.experimental.daemon

import java.io.DataInputStream
import java.net.Socket

/**
 * Reader for the daemon's stream. The format is scrcpy's, unchanged, because
 * the fork left Streamer unpatched:
 *
 *     4 bytes   codec id, ASCII ("opus", "raw ")
 *     repeating:
 *       8 bytes pts, big endian, with flags in the top three bits
 *       4 bytes payload length
 *       n bytes payload
 */
class AudioStream(private val socket: Socket) : AutoCloseable {

    private val input: DataInputStream = DataInputStream(socket.getInputStream().buffered())
    private var completed = false
    private val completionMarker = "VRECEND!".toByteArray(Charsets.US_ASCII)

    /** Sent once, before any packet. */
    val codecId: String = ByteArray(4)
        .also { input.readFully(it) }
        .toString(Charsets.US_ASCII)
        .trim { it <= ' ' }

    /** The helper uses this codec-slot reply for files no longer ready to fetch. */
    val unavailable: Boolean get() = codecId == "gone"

    data class Packet(
        val pts: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val data: ByteArray,
    ) {
        // Data class equality on a ByteArray compares references, which is
        // never what anyone means. These exist only to stop that being a trap.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Packet && pts == other.pts && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * pts.hashCode() + data.contentHashCode()
    }

    /** Next packet, or null once the daemon closes the stream. */
    fun readPacket(): Packet? {
        // EOF exactly between packets is fine. Partial packet headers are
        // corruption, not a clean end-of-recording, and must abort import.
        if (completed) return null
        val rawHeader = ByteArray(8)
        // A socket closing between complete packets is still a truncated fetch.
        // Require the explicit completion trailer sent by the shell daemon.
        input.readFully(rawHeader)
        val length = input.readInt()
        if (rawHeader.contentEquals(completionMarker)) {
            if (length != -1) error("Invalid shell recording completion marker")
            completed = true
            return null
        }
        val raw = java.nio.ByteBuffer.wrap(rawHeader)
            .order(java.nio.ByteOrder.BIG_ENDIAN).long
        if (length < 0 || length > MAX_PACKET) {
            error("implausible packet length $length; the stream is out of sync")
        }
        val data = ByteArray(length)
        // No silent success on truncated audio: the caller will keep the
        // source file for recovery/diagnostics rather than publish bad Ogg.
        input.readFully(data)
        return Packet(
            pts = raw and PTS_MASK,
            isConfig = raw and FLAG_CONFIG != 0L,
            isKeyFrame = raw and FLAG_KEY_FRAME != 0L,
            data = data,
        )
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { socket.close() }
    }

    private companion object {
        const val FLAG_SESSION = 1L shl 63
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = (FLAG_SESSION or FLAG_CONFIG or FLAG_KEY_FRAME).inv()

        /** A sane ceiling. Opus frames are tens of bytes; a raw PCM chunk is a
         *  few KB. Anything near this means the stream desynchronised, and
         *  failing loudly beats allocating whatever a corrupt length asked for. */
        const val MAX_PACKET = 1 shl 20
    }
}
