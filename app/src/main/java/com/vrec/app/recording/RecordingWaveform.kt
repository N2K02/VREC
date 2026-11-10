package com.vrec.app.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Bounded waveform preview of saved audio.
 *
 * Sequentially decoding an hour-long recording on playback startup previously hit
 * the deadline and returned null. Instead, decode small representative windows
 * across the entire track. The caller runs this on Dispatchers.IO.
 */
object RecordingWaveform {
    const val BARS = 64
    private const val DEADLINE_MS = 12_000L
    private val cache = LruCache<String, FloatArray>(12)

    fun calculate(file: File): FloatArray? {
        if (!file.isFile || file.length() == 0L) return null
        val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        synchronized(cache) { cache.get(key) }?.let { return it.copyOf() }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0L) return null
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val peaks = FloatArray(BARS)
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
            var visited = 0
            for (bar in 0 until BARS) {
                if (SystemClock.elapsedRealtime() >= deadline) break
                val positionUs = (durationUs / BARS) * bar
                extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                decoder.flush()
                // Audio decoders often need two packets after a seek before
                // producing PCM. Bound both input and output per window.
                repeat(3) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                        if (input != null) {
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size >= 0) {
                                decoder.queueInputBuffer(inputIndex, 0, size,
                                    extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                    }
                }
                var peak = 0f
                repeat(5) {
                    val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                    if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 1) {
                            val data = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            val samples = data.slice().order(ByteOrder.LITTLE_ENDIAN)
                            val pcmFormat = decoder.outputFormat
                            val encoding = if (pcmFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                pcmFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floats = samples.asFloatBuffer()
                                val step = max(1, floats.remaining() / 256)
                                while (floats.hasRemaining()) {
                                    peak = max(peak, abs(floats.get()).coerceIn(0f, 1f))
                                    floats.position((floats.position() + step - 1).coerceAtMost(floats.limit()))
                                }
                            } else {
                                val shorts = samples.asShortBuffer()
                                val step = max(1, shorts.remaining() / 256)
                                while (shorts.hasRemaining()) {
                                    peak = max(peak, abs(shorts.get().toInt()) / 32768f)
                                    shorts.position((shorts.position() + step - 1).coerceAtMost(shorts.limit()))
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
                peaks[bar] = peak.coerceIn(0f, 1f)
                visited++
            }
            // Do not mistake a timed-out full-file scan for an empty waveform.
            // Fill unvisited tail with the last representative value.
            if (visited == 0) return null
            for (bar in visited until BARS) peaks[bar] = peaks[visited - 1]
            val maxPeak = peaks.maxOrNull() ?: 0f
            if (maxPeak > 0.001f) {
                for (bar in peaks.indices) peaks[bar] = (peaks[bar] / maxPeak).coerceIn(0f, 1f)
            }
            synchronized(cache) { cache.put(key, peaks.copyOf()) }
            return peaks
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
