package com.vrec.app.experimental.daemon

import java.io.File
import java.io.FileOutputStream

/**
 * Transaction-like publication of a validated Ogg and its JSON sidecar.
 *
 * The original shell capture is only deleted by the caller after publication
 * succeeds. Files moved to .previous are never deleted on incomplete commits.
 * A durable .committed marker distinguishes a complete replacement from an
 * interrupted move of audio and metadata.
 */
internal object RecordingAudioPublisher {
    private fun metadata(audio: File) = File(audio.parentFile, audio.name + ".json")
    private fun backup(file: File) = File(file.parentFile, file.name + ".previous")
    private fun committed(dest: File) = File(dest.parentFile, dest.name + ".committed")

    private fun syncFile(file: File) {
        // File content must be durable before a rename-based commit marker.
        // Opening in append mode does not rewrite the existing recording.
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun remove(file: File) {
        if (file.exists()) check(file.delete()) { "Cannot remove obsolete recording transaction file" }
    }

    private fun move(from: File, to: File) {
        check(from.renameTo(to)) { "Cannot move recording transaction file" }
    }

    private fun cleanup(dest: File) {
        remove(backup(metadata(dest)))
        remove(backup(dest))
        remove(committed(dest))
    }

    /** Recover both sides of an interrupted replacement, not audio alone. */
    fun recover(dest: File, validMetadata: (File) -> Boolean) {
        val sidecar = metadata(dest)
        val audioBackup = backup(dest)
        val metadataBackup = backup(sidecar)
        val marker = committed(dest)
        if (!audioBackup.exists() && !metadataBackup.exists() && !marker.exists()) return

        if (marker.exists() && OggIntegrityVerifier.valid(dest) && validMetadata(dest)) {
            // Completed transaction; cleanup can be resumed safely after a crash.
            cleanup(dest)
            return
        }

        // Never trust a new Ogg merely because its CRC passes: its metadata may
        // not be the matching version. Restore the older pair if available.
        if (metadataBackup.exists()) {
            remove(sidecar)
            move(metadataBackup, sidecar)
        } else if (audioBackup.exists()) {
            // The prior recording had no sidecar; remove newly published metadata.
            remove(sidecar)
        }
        if (audioBackup.exists()) {
            remove(dest)
            move(audioBackup, dest)
        }
        remove(marker)
    }

    /** The temporary audio and its metadata must both be validated first. */
    fun publish(temp: File, dest: File, validMetadata: (File) -> Boolean) {
        check(OggIntegrityVerifier.valid(temp)) { "Cannot publish invalid recording audio" }
        check(validMetadata(temp)) { "Cannot publish invalid recording metadata" }
        syncFile(temp)
        syncFile(metadata(temp))

        val incomingMeta = metadata(temp)
        val sidecar = metadata(dest)
        val audioBackup = backup(dest)
        val metadataBackup = backup(sidecar)
        val marker = committed(dest)
        check(!marker.exists() && !audioBackup.exists() && !metadataBackup.exists()) {
            "Recording transaction requires recovery before retry"
        }
        // Stage existing metadata before audio. If either backup exists after a
        // crash, recovery restores the matching old file pair.
        try {
            if (sidecar.exists()) move(sidecar, metadataBackup)
            if (dest.exists()) move(dest, audioBackup)
            move(temp, dest)
            move(incomingMeta, sidecar)
            check(OggIntegrityVerifier.valid(dest) && validMetadata(dest)) {
                "Final recording audio or metadata verification failed"
            }
            FileOutputStream(marker).use {
                it.write(1)
                it.fd.sync()
            }
        } catch (failure: Throwable) {
            // If a backup exists, restore BOTH old files. If none exists,
            // retain the uncommitted files: the shell original is still safe
            // and the importer can retry and replace them next time.
            try {
                recover(dest, validMetadata)
            } catch (restoreFailure: Throwable) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
        // Backups are removed only after both new files and the durable marker
        // exist. On cleanup failure, recovery can resume at the next import.
        cleanup(dest)
    }
}
