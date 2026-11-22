package com.vrec.app.experimental.daemon

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Small Ogg/Opus page writer for Android 8 and 9 (API 26-28).
 * Android's MediaMuxer does not support Ogg/Opus on these releases.
 * The caller owns the output descriptor and must close it after this writer.
 */
internal class LegacyOpusOggMuxer(
    private val output: OutputStream,
    opusHead: ByteArray,
    private val serial: Int = SecureRandom().nextInt(),
) : AutoCloseable {
    private var sequence = 0
    private var packetCount = 0L
    private var pending: ByteArray? = null
    private var finished = false

    init {
        OpusHead.parse(opusHead)
        writePage(opusHead, 0L, 0x02)
        val vendor = "VRec".toByteArray(Charsets.UTF_8)
        val tags = ByteBuffer.allocate(8 + 4 + vendor.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII))
            .putInt(vendor.size).put(vendor).putInt(0).array()
        writePage(tags, 0L, 0)
    }

    fun write(packet: ByteArray) {
        check(!finished) { "Ogg output is already finalized" }
        require(packet.isNotEmpty() && packet.size < 65_025) { "Invalid Opus packet length" }
        pending?.let { writePage(it, packetCount * 960L, 0) }
        pending = packet.copyOf()
        packetCount++
    }

    override fun close() {
        if (finished) return
        finished = true
        pending?.let { writePage(it, packetCount * 960L, 0x04) }
        output.flush()
        pending = null
    }

    private fun writePage(packet: ByteArray, granule: Long, flags: Int) {
        val segments = packet.size / 255 + 1
        require(segments <= 255) { "Ogg page exceeds 255 lacing segments" }
        val page = ByteBuffer.allocate(27 + segments + packet.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray(Charsets.US_ASCII))
        page.put(0) // Ogg stream structure version
        page.put(flags.toByte())
        page.putLong(granule)
        page.putInt(serial)
        page.putInt(sequence++)
        page.putInt(0) // CRC placeholder
        page.put(segments.toByte())
        repeat(segments - 1) { page.put(255.toByte()) }
        page.put((packet.size % 255).toByte())
        page.put(packet)
        val bytes = page.array()
        var crc = 0
        for (value in bytes) {
            crc = crc xor ((value.toInt() and 0xff) shl 24)
            repeat(8) {
                crc = (crc shl 1) xor
                    (if ((crc and Int.MIN_VALUE) != 0) 0x04c11db7 else 0)
            }
        }
        for (i in 0..3) bytes[22 + i] = (crc ushr (8 * i)).toByte()
        output.write(bytes)
    }
}
