package com.vrec.app.recording

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.vrec.app.Log
import com.vrec.app.Prefs
import java.io.File

/**
 * Records one call to an .m4a file, working around Android's call-audio limits as far as a
 * no-root / no-Shizuku app can.
 *
 * Since Android 10 an ordinary app is handed silence while a call is active; VREC relies on its
 * accessibility service being enabled so the system treats it as a privileged capturer. Even
 * then, which microphone source actually carries the other party varies a lot by phone, Android
 * version, headset and Wi-Fi calling. So VREC does not trust one source: in "auto" mode it tries
 * a chain of sources and keeps the first that produces sound, optionally putting the call on
 * loudspeaker as a last resort so the mic can at least pick up both sides in the room.
 *
 * Nothing here needs root, Shizuku, ADB or wireless/USB debugging.
 */
class RecordingEngine(private val context: Context) {

    /** A microphone source VREC can try, in preference order for "auto" mode. */
    enum class Source(val label: String, val androidSource: Int, val forceSpeaker: Boolean) {
        /** Some OEMs still wire this to the full two-way call line. Blocked/silent on many phones. */
        VOICE_CALL("Voice call line", MediaRecorder.AudioSource.VOICE_CALL, false),
        /** Cube's recommended combo with in-communication mode; often the best two-way result. */
        VOICE_RECOGNITION("Voice recognition", MediaRecorder.AudioSource.VOICE_RECOGNITION, false),
        /** Communication-tuned mic; echo-cancelled, usually your side plus some of theirs. */
        VOICE_COMMUNICATION("Communication mic", MediaRecorder.AudioSource.VOICE_COMMUNICATION, false),
        /** Plain mic. With loudspeaker on it captures both sides from the room. Always works. */
        MIC_SPEAKER("Microphone + loudspeaker", MediaRecorder.AudioSource.MIC, true);

        companion object {
            fun fromName(name: String?): Source? = entries.firstOrNull { it.name == name }
        }
    }

    data class Result(
        val file: File,
        val source: Source,
        val silent: Boolean,
        val durationMs: Long,
    )

    private var recorder: MediaRecorder? = null
    private var activeSource: Source? = null
    private var startedAt = 0L
    private var accumulatedDurationMs = 0L
    private var paused = false
    private var target: File? = null

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private var touchedAudioSettings = false

    @Volatile
    var maxAmplitudeSeen = 0
        private set

    companion object {
        /** How long to listen on a source before deciding it is silent (blocked). */
        private const val PROBE_MS = 2500L
        private const val PROBE_STEP_MS = 150L
        /** Peak amplitude (0..32767) below which a source is treated as producing no sound. */
        private const val SILENCE_AMP = 120
    }

    /**
     * Starts recording to [file]. In auto mode this blocks briefly (up to a few seconds) while it
     * probes sources for one that produces sound, then leaves that source running. Call from a
     * background thread. Returns the source chosen, or null if recording could not start at all.
     */
    fun start(file: File): Source? {
        target = file
        file.parentFile?.mkdirs()

        val chain: List<Source> = when (val mode = Prefs.sourceMode(context)) {
            Prefs.SOURCE_MODE_AUTO -> buildList {
                add(Source.VOICE_CALL)
                add(Source.VOICE_RECOGNITION)
                add(Source.VOICE_COMMUNICATION)
                if (Prefs.autoSpeaker(context)) add(Source.MIC_SPEAKER)
            }
            else -> listOfNotNull(Source.fromName(mode))
        }.ifEmpty { listOf(Source.VOICE_COMMUNICATION) }

        applyAudioSettings()

        for ((index, source) in chain.withIndex()) {
            val last = index == chain.lastIndex
            if (tryStart(source, file)) {
                // The last source in the chain is kept even if silent — a silent file still
                // records your side on most phones, and tells you this device blocks the rest.
                if (last || !probeIsSilent()) {
                    activeSource = source
                    startedAt = SystemClock.elapsedRealtime()
                    accumulatedDurationMs = 0L
                    paused = false
                    Log.i("Recording with \"${source.label}\"" + if (last) " (last fallback)" else "")
                    return source
                }
                Log.i("\"${source.label}\" produced only silence; trying the next source")
                stopRecorderQuietly()
                runCatching { if (file.exists()) file.delete() }
            }
        }

        restoreAudioSettings()
        Log.w("No microphone source could start a recording on this phone")
        return null
    }

    /** Pauses capture without finalizing the file. Returns false if no active recording exists. */
    fun pause(): Boolean {
        if (activeSource == null || paused) return false
        return try {
            recorder?.pause() ?: return false
            accumulatedDurationMs += SystemClock.elapsedRealtime() - startedAt
            paused = true
            true
        } catch (e: Exception) {
            Log.w("Could not pause recording", e)
            false
        }
    }

    /** Resumes capture in the same file. Returns false if the recorder is not paused. */
    fun resume(): Boolean {
        if (activeSource == null || !paused) return false
        return try {
            recorder?.resume() ?: return false
            startedAt = SystemClock.elapsedRealtime()
            paused = false
            true
        } catch (e: Exception) {
            Log.w("Could not resume recording", e)
            false
        }
    }

    /** Active recording duration, excluding time spent paused. */
    fun elapsedRecordingMs(): Long =
        accumulatedDurationMs + if (activeSource != null && !paused) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Finalizes the recording and returns what was captured, or null if nothing was recording. */
    fun stop(): Result? {
        val source = activeSource ?: run { restoreAudioSettings(); return null }
        val file = target ?: return null
        val duration = elapsedRecordingMs()
        stopRecorderQuietly()
        restoreAudioSettings()
        activeSource = null
        paused = false
        accumulatedDurationMs = 0L
        val silent = maxAmplitudeSeen < SILENCE_AMP
        if (silent) Log.w("Recording finished but stayed silent — this phone likely blocks call capture on \"${source.label}\"")
        return if (file.exists() && file.length() > 0) {
            Result(file, source, silent, duration)
        } else {
            Log.w("Recording file was empty")
            null
        }
    }

    // --------------------------------------------------------------- internals

    private fun tryStart(source: Source, file: File): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(source.androidSource)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(file.absolutePath)
            if (source.forceSpeaker) setSpeaker(true)
            r.prepare()
            r.start()
            recorder = r
            maxAmplitudeSeen = 0
            true
        } catch (e: Exception) {
            Log.w("Source \"${source.label}\" couldn't start", e)
            stopRecorderQuietly()
            false
        }
    }

    /** Listens on the just-started recorder; true if it stays below the silence threshold. */
    private fun probeIsSilent(): Boolean {
        val r = recorder ?: return true
        var peak = 0
        var elapsed = 0L
        // First read after start is always 0; ignore it.
        runCatching { r.maxAmplitude }
        while (elapsed < PROBE_MS) {
            try {
                Thread.sleep(PROBE_STEP_MS)
            } catch (_: InterruptedException) {
                break
            }
            elapsed += PROBE_STEP_MS
            peak = maxOf(peak, runCatching { r.maxAmplitude }.getOrDefault(0))
            if (peak >= SILENCE_AMP) break
        }
        maxAmplitudeSeen = maxOf(maxAmplitudeSeen, peak)
        return peak < SILENCE_AMP
    }

    /** Called periodically by the service to keep a running peak for the UI level meter. */
    fun sampleAmplitude() {
        val r = recorder ?: return
        maxAmplitudeSeen = maxOf(maxAmplitudeSeen, runCatching { r.maxAmplitude }.getOrDefault(0))
    }

    private fun stopRecorderQuietly() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.reset() }
        runCatching { r.release() }
    }

    private fun applyAudioSettings() {
        if (!Prefs.inCommMode(context) && !Prefs.autoSpeaker(context)) return
        savedMode = audio.mode
        savedSpeaker = isSpeakerOn()
        touchedAudioSettings = true
        if (Prefs.inCommMode(context)) {
            runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
        }
        if (Prefs.autoSpeaker(context)) setSpeaker(true)
    }

    private fun restoreAudioSettings() {
        if (!touchedAudioSettings) return
        touchedAudioSettings = false
        runCatching { setSpeaker(savedSpeaker) }
        runCatching { audio.mode = savedMode }
    }

    @Suppress("DEPRECATION")
    private fun isSpeakerOn(): Boolean = audio.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setSpeaker(on: Boolean) {
        runCatching { audio.isSpeakerphoneOn = on }
    }
}
