package com.vrec.app.experimental.daemon

/** Parse packet duration from the Opus TOC, independent of transport timestamps.
 * Opus permits 2.5–120 ms packets; assuming 20 ms corrupts duration on
 * encoder frame-size changes even when every packet arrives intact.
 */
internal object OpusPacketTiming {
    fun samples(packet: ByteArray): Int {
        require(packet.isNotEmpty()) { "Empty Opus audio packet" }
        val toc = packet[0].toInt() and 255
        val config = toc ushr 3
        val frameSamples = when {
            config < 12 -> when (config and 3) {
                0 -> 480
                1 -> 960
                2 -> 1920
                else -> 2880
            }
            config < 16 -> if (config and 1 == 0) 480 else 960
            else -> 120 shl (config and 3)
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> {
                require(packet.size > 1) { "Missing Opus frame-count byte" }
                packet[1].toInt() and 63
            }
        }
        require(count in 1..48) { "Invalid Opus frame count" }
        val total = frameSamples * count
        require(total in 120..5760) { "Invalid Opus packet duration" }
        return total
    }
}
