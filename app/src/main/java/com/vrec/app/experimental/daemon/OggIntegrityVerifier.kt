package com.vrec.app.experimental.daemon

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Validates a completed single-stream Ogg/Opus recording before the detached
 * shell original can be discarded. Checks every page's framing, lacing and
 * CRC, serial and page continuity, Opus headers, and the final EOS flag.
 * A metadata sidecar or file size alone cannot establish audio integrity.
 */
internal object OggIntegrityVerifier {
    fun valid(file: File): Boolean =
        file.isFile && file.length() > 100L && runCatching {
            FileInputStream(file).buffered().use(::verify)
        }.getOrDefault(false)

    private fun verify(input: InputStream): Boolean {
        var pageIndex = 0
        var serial = 0
        var lastGranule = 0L
        var sawAudio = false
        var sawEos = false
        while (true) {
            val header = ByteArray(27)
            val first = input.read()
            if (first < 0) break
            header[0] = first.toByte()
            if (!readExactly(input, header, 1, 26)) return false
            if (String(header, 0, 4, Charsets.US_ASCII) != "OggS" ||
                header[4].toInt() != 0) return false
            val flags = header[5].toInt() and 255
            if (flags and 0xF8 != 0 || flags and 0x01 != 0) return false
            if (pageIndex == 0 && flags and 0x02 == 0) return false
            if (pageIndex != 0 && flags and 0x02 != 0) return false
            val nextSerial = littleInt(header, 14)
            if (pageIndex == 0) serial = nextSerial
            if (nextSerial != serial || littleInt(header, 18) != pageIndex) return false
            val granule = littleLong(header, 6)
            if (granule < lastGranule || granule < 0L) return false
            val count = header[26].toInt() and 255
            if (count == 0) return false
            val lacing = ByteArray(count)
            if (!readExactly(input, lacing, 0, count)) return false
            val length = lacing.sumOf { it.toInt() and 255 }
            val payload = ByteArray(length)
            if (!readExactly(input, payload, 0, length)) return false
            // Writer uses one complete packet per page; refuse continuations
            // until multi-packet import can be validated explicitly.
            if ((lacing.last().toInt() and 255) == 255) return false
            val packet = ByteArray(header.size + lacing.size + payload.size)
            System.arraycopy(header, 0, packet, 0, header.size)
            System.arraycopy(lacing, 0, packet, header.size, lacing.size)
            System.arraycopy(payload, 0, packet, header.size + lacing.size, payload.size)
            val claimed = littleInt(header, 22)
            packet.fill(0, 22, 26)
            if (checksum(packet) != claimed) return false
            when (pageIndex) {
                0 -> if (!payload.copyOfRange(0, minOf(8, payload.size))
                    .contentEquals("OpusHead".toByteArray(Charsets.US_ASCII))) return false
                1 -> if (!payload.copyOfRange(0, minOf(8, payload.size))
                    .contentEquals("OpusTags".toByteArray(Charsets.US_ASCII))) return false
                else -> {
                    if (payload.isEmpty()) return false
                    sawAudio = true
                }
            }
            if (flags and 0x04 != 0) {
                sawEos = true
                // Reject trailing pages or garbage after the final EOS page.
                if (input.read() != -1) return false
                break
            }
            pageIndex++
            lastGranule = granule
        }
        return sawEos && sawAudio && pageIndex >= 2
    }

    private fun readExactly(input: InputStream, bytes: ByteArray, offset: Int, length: Int): Boolean {
        var done = 0
        while (done < length) {
            val n = input.read(bytes, offset + done, length - done)
            if (n <= 0) return false
            done += n
        }
        return true
    }

    private fun littleInt(bytes: ByteArray, start: Int): Int {
        var value = 0
        for (i in 0..3) value = value or ((bytes[start + i].toInt() and 255) shl (8 * i))
        return value
    }

    private fun littleLong(bytes: ByteArray, start: Int): Long {
        var value = 0L
        for (i in 0..7) value = value or ((bytes[start + i].toLong() and 255L) shl (8 * i))
        return value
    }

    private fun checksum(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) {
                crc = (crc shl 1) xor (if (crc and Int.MIN_VALUE != 0) 0x04c11db7 else 0)
            }
        }
        return crc
    }
}
