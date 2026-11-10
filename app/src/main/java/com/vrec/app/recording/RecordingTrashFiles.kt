package com.vrec.app.recording

import java.io.File

/** Pure file operations for moving call recordings and their JSON metadata safely. */
internal object RecordingTrashFiles {
    fun move(audio: File, destination: File): Boolean {
        if (!audio.isFile || destination.exists() || audio.canonicalPath == destination.canonicalPath)
            return false
        val targetParent = destination.parentFile ?: return false
        if (!targetParent.exists() && !targetParent.mkdirs()) return false
        if (!audio.renameTo(destination)) return false
        val sourceMetadata = File(audio.parentFile, audio.name + ".json")
        val destinationMetadata = File(destination.parentFile, destination.name + ".json")
        if (sourceMetadata.exists() &&
            (destinationMetadata.exists() || !sourceMetadata.renameTo(destinationMetadata))) {
            // Never silently hide metadata loss: roll back the move if possible.
            if (!destination.renameTo(audio)) {
                throw IllegalStateException("Unable to restore audio after metadata move failed")
            }
            return false
        }
        return true
    }
}
