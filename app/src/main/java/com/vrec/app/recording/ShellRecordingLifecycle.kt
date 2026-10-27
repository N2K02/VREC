package com.vrec.app.recording

/**
 * Serializes shell wake events by recording identity. A delayed START after END
 * must not resurrect the overlay or recording notification.
 */
internal class ShellRecordingLifecycle {
    private val ended = LinkedHashSet<String>()
    private var current: String? = null

    @Synchronized fun started(file: String): Boolean {
        if (!valid(file) || identity(file) in ended) return false
        if (current == file) return false
        if (current != null) return false
        current = file
        return true
    }

    @Synchronized fun ended(file: String): Boolean {
        if (!valid(file)) return false
        ended.add(identity(file))
        while (ended.size > 64) ended.remove(ended.first())
        if (current == null || identity(current!!) != identity(file)) return false
        current = null
        return true
    }

    /** Pause closes the current file; Resume must accept a fresh STARTED file. */
    @Synchronized fun pausedByUser(): Boolean {
        val file = current ?: return false
        ended.add(identity(file))
        while (ended.size > 64) ended.remove(ended.first())
        current = null
        return true
    }

    /** A confirmed user stop ends the current generation even if END is delayed. */
    @Synchronized fun stoppedByUser(): Boolean {
        val file = current ?: return false
        ended.add(identity(file))
        while (ended.size > 64) ended.remove(ended.first())
        current = null
        return true
    }

    @Synchronized fun reset() {
        current = null
        ended.clear()
    }

    @Synchronized fun isActive(): Boolean = current != null

    private fun identity(file: String): String =
        file.removePrefix("vrec_rec_").substringBefore("_").substringBefore(".")

    private fun valid(file: String): Boolean =
        file.startsWith("vrec_rec_") && file.endsWith(".dat") &&
            file.none { it == '/' || it == '\\' } && file.length <= 200
}
