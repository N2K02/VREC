package com.vrec.app.experimental.daemon

import java.io.File

/**
 * Publishes verified audio without deleting an existing local recording first.
 * A previous recording is moved aside and restored if publication fails.
 * A backup left by process death is recovered on the next import attempt.
 */
internal object RecordingAudioPublisher {
    private fun backup(dest: File) = File(dest.parentFile, dest.name + ".previous")

    fun recover(dest: File) {
        val previous = backup(dest)
        if (!previous.exists()) return
        if (dest.exists() && OggIntegrityVerifier.valid(dest)) {
            // New recording was already published successfully before a crash.
            check(previous.delete()) { "Cannot clean up previous recording backup" }
            return
        }
        if (dest.exists()) check(dest.delete()) { "Cannot remove damaged published audio" }
        check(previous.renameTo(dest)) { "Cannot recover previous recording audio" }
    }

    fun publish(temp: File, dest: File) {
        check(OggIntegrityVerifier.valid(temp)) { "Cannot publish invalid recording audio" }
        val previous = backup(dest)
        check(!previous.exists()) { "Previous recording recovery is still pending" }
        if (dest.exists()) check(dest.renameTo(previous)) {
            "Cannot preserve existing recording before replacement"
        }
        try {
            check(temp.renameTo(dest)) { "Cannot finalize recording" }
            check(OggIntegrityVerifier.valid(dest)) {
                "Final recording integrity verification failed"
            }
        } catch (failure: Throwable) {
            // Do not overwrite or lose the previous local recording.
            if (previous.exists()) {
                if (dest.exists()) check(dest.delete()) {
                    "Cannot clear damaged replacement; prior audio remains in .previous"
                }
                check(previous.renameTo(dest)) {
                    "Cannot restore previous recording; backup remains in .previous"
                }
            }
            throw failure
        }
        if (previous.exists()) check(previous.delete()) {
            "Recording saved but old backup could not be cleaned up"
        }
    }
}
