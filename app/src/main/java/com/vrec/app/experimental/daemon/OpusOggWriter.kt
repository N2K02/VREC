package com.vrec.app.experimental.daemon

import android.os.Build
import java.io.FileOutputStream
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps the daemon's Opus packets into a playable .ogg file.
 *
 * Until this existed the recordings were raw Opus packets with no container:
 * real audio, provably so, and openable by nothing. A container is what turns
 * the bytes into a file someone can actually listen to.
 *
 * WHAT THE CONFIG PACKET IS
 *
 * The first thing the daemon sends after the codec id is a config packet, and
 * scrcpy has already done the hard part of extracting it. MediaCodec hands out
 * Opus CSD as three length-prefixed sections - AOPUSHDR, AOPUSDLY, AOPUSPRL -
 * and Streamer.fixOpusConfigPacket() slices out just the first, so what arrives
 * here is a bare 19-byte OpusHead:
 *
 *   4f 70 75 73 48 65 61 64  "OpusHead"
 *   01                       version
 *   02                       channel count
 *   38 01                    pre-skip, little endian (312)
 *   80 bb 00 00              input sample rate (48000)
 *   00 00                    output gain
 *   00                       channel mapping family
 *
 * MediaMuxer wants that back as three separate pieces of codec-specific data,
 * so the other two are reconstructed from it rather than guessed:
 *
 *   csd-0  the OpusHead itself
 *   csd-1  codec delay in NANOSECONDS, which is pre-skip converted at 48 kHz
 *   csd-2  seek pre-roll in nanoseconds, 80 ms by convention
 *
 * Deriving csd-1 from the header's own pre-skip matters: hardcoding 6.5 ms
 * happens to be right for a pre-skip of 312 and silently wrong for anything
 * else, and the failure is a file that plays with the wrong A/V offset rather
 * than one that refuses to open.
 */
class OpusOggWriter(
    fd: FileDescriptor,
    opusHead: ByteArray,
) : AutoCloseable {

    private val head = OpusHead.parse(opusHead)
    // Ogg output format is only available on Android 10 (API 29) and later.
    // Keep the legacy output stream open: the caller owns the file descriptor.
    private val legacy = if (Build.VERSION.SDK_INT < 29)
        LegacyOpusOggMuxer(FileOutputStream(fd), opusHead) else null
    private val muxer = if (Build.VERSION.SDK_INT >= 29)
        MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG) else null
    private var track: Int = -1
    private var started = false

    /** Packets written. Zero means the call produced nothing. The timestamp
     *  handed to the muxer is COUNTED from this, not clocked: packet N sits at
     *  N x 20ms. See write() for why nothing else works here. */
    var packets: Long = 0L
        private set

    init {
        Log.i(TAG, "ogg: channels=${head.channels} preSkip=${head.preSkip} rate=${head.inputRate} delay=${head.codecDelayNs}ns")

        if (legacy != null) {
            started = true
            return
        }
        val androidMuxer = requireNotNull(muxer)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, head.channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead))
        format.setByteBuffer("csd-1", littleEndianLong(head.codecDelayNs))
        format.setByteBuffer("csd-2", littleEndianLong(SEEK_PRE_ROLL_NS))

        try {
            track = androidMuxer.addTrack(format)
            androidMuxer.start()
            started = true
        } catch (failure: Exception) {
            runCatching { androidMuxer.release() }
            throw failure
        }
    }

    /**
     * @param pts the stream's own PTS - IGNORED, see below.
     *
     * WHY THE TIMESTAMP IS COUNTED, NOT TAKEN FROM THE STREAM OR THE CLOCK
     *
     * MediaMuxer.writeSampleData SILENTLY DROPS any sample whose
     * presentationTimeUs is not STRICTLY GREATER than the previous one, and
     * offers no error when it does. The scrcpy Opus stream's own PTS is not
     * dependably increasing (it is regenerated from wall-clock on the device and
     * jitters), so feeding it through here dropped ~80% of a call - measured, a
     * 76.7s capture became a 14-44s file. A wall-clock stamp taken HERE has the
     * same hazard the other way: when this reader is starved (the OS freezing a
     * backgrounded app), the timestamps bunch up and the file plays fast.
     *
     * The encoder emits one Opus frame every 20ms, continuously - no gaps, no
     * dropped silence (confirmed on the device: 3837 packets across 76.7s). So
     * the Nth audio packet simply BELONGS at N x 20ms. Counting is exact,
     * strictly monotonic by construction, and completely independent of when
     * this code happens to run - which is the whole point now that the file is
     * produced by the daemon and read back here at whatever pace the OS allows.
     */
    fun write(data: ByteArray, @Suppress("UNUSED_PARAMETER") pts: Long) {
        if (!started) return

        if (legacy != null) {
            legacy.write(data)
            packets++
            return
        }
        val ptsUs = packets * FRAME_US

        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = ptsUs
            flags = 0
        }
        requireNotNull(muxer).writeSampleData(track, ByteBuffer.wrap(data), info)
        packets++
    }

    /** Length of what was written, in seconds. Every packet is a frame, the
     *  last one included: this was the last packet's timestamp, which is one
     *  frame short of the audio written. */
    val durationSeconds: Double
        get() = packets * FRAME_US / 1_000_000.0

    /**
     * Finalises the file. Skipping this leaves an .ogg that no player will
     * open, so it is what "correct file finalize on hangup" actually means -
     * the muxer has to be stopped, not merely abandoned.
     */
    override fun close() {
        if (!started) return
        started = false
        if (legacy != null) {
            legacy.close()
            return
        }
        val androidMuxer = requireNotNull(muxer)
        try {
            if (packets > 0) androidMuxer.stop()
        } finally {
            androidMuxer.release()
        }
    }

    private companion object {
        const val TAG = "VRec"
        const val SAMPLE_RATE = 48_000

        /** 80 ms, the value Opus and Android both use by convention. */
        const val SEEK_PRE_ROLL_NS = 80_000_000L

        /** One Opus frame is 20 ms. The encoder emits exactly one per packet, so
         *  packet index times this is the packet's place on the timeline. */
        const val FRAME_US = 20_000L

        fun littleEndianLong(value: Long): ByteBuffer =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).apply { flip() }
    }
}
