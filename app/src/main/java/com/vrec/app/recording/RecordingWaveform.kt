package com.vrec.app.recording

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** On-demand, bounded PCM decode of saved audio. Never touches a live recording. */
object RecordingWaveform {
    const val BARS = 96

    fun calculate(file: File): FloatArray? {
        if (!file.isFile || file.length() == 0L) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val peaks = FloatArray(BARS)
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val idx = decoder.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buffer = decoder.getInputBuffer(idx) ?: break
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val idx = decoder.dequeueOutputBuffer(info, 10_000)
                if (idx >= 0) {
                    val output = decoder.getOutputBuffer(idx)
                    if (output != null && info.size > 1) {
                        val sample = output.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        sample.position(info.offset)
                        sample.limit(info.offset + info.size)
                        val shorts = sample.slice().asShortBuffer()
                        val bar = ((info.presentationTimeUs * BARS) / durationUs)
                            .toInt().coerceIn(0, BARS - 1)
                        var peak = 0f
                        // Bound CPU work on long recordings, sampling evenly within each output buffer.
                        val step = max(1, shorts.remaining() / 192)
                        while (shorts.hasRemaining()) {
                            peak = max(peak, abs(shorts.get().toInt()) / 32768f)
                            val skip = minOf(step - 1, shorts.remaining())
                            shorts.position(shorts.position() + skip)
                        }
                        peaks[bar] = max(peaks[bar], peak)
                    }
                    decoder.releaseOutputBuffer(idx, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
            if (!outputEnded) return null
            val high = peaks.maxOrNull()?.coerceAtLeast(0.001f) ?: 0.001f
            return FloatArray(BARS) { (peaks[it] / high).coerceIn(0f, 1f) }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
