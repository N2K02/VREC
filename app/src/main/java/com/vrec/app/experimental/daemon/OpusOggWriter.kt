package com.vrec.app.experimental.daemon

import java.io.FileDescriptor
import java.io.FileOutputStream
import android.util.Log

/**
 * Deterministic Opus-in-Ogg writer for recordings fetched from the shell helper.
 *
 * All supported Android versions now write identical Ogg pages using the
 * tested Kotlin muxer. Previously Android >= 29 used MediaMuxer, whose page
 * structure/finalization differed from the local writer and could be rejected
 * by the strict import-integrity verifier. The original shell file remains
 * available if any write, flush, or verification operation fails.
 */
class OpusOggWriter(
    fd: FileDescriptor,
    opusHead: ByteArray,
) : AutoCloseable {
    private val head = OpusHead.parse(opusHead)
    private val ogg = LegacyOpusOggMuxer(FileOutputStream(fd), opusHead)
    private var closed = false
    private var totalSamples = 0L

    var packets: Long = 0L
        private set

    init {
        Log.i(TAG, "ogg: channels=${head.channels} preSkip=${head.preSkip} rate=${head.inputRate}")
    }

    /**
     * The transport PTS is intentionally ignored: scrcpy timestamps may jitter
     * and MediaMuxer silently discarded many valid Opus packets when used.
     * Each packet's Opus TOC supplies the exact encoded duration instead.
     */
    fun write(data: ByteArray, @Suppress("UNUSED_PARAMETER") pts: Long) {
        check(!closed) { "Ogg recording has already been finalized" }
        val samples = OpusPacketTiming.samples(data)
        ogg.write(data)
        totalSamples += samples
        packets++
    }

    val durationSeconds: Double
        get() = totalSamples.toDouble() / SAMPLE_RATE

    override fun close() {
        if (closed) return
        closed = true
        // flush/final EOS failures must propagate to the importer, which keeps
        // the shell original. Never report a successful import if close fails.
        ogg.close()
    }

    private companion object {
        const val TAG = "VRec"
        const val SAMPLE_RATE = 48_000
    }
}
