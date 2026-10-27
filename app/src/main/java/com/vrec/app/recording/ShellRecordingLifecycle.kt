package com.vrec.app.recording

/**
 * Serializes shell wake events by recording identity. A delayed START after END
 * must not resurrect the overlay or recording notification.
 */
internal class ShellRecordingLifecycle {
    private val ended = LinkedHashSet<String>()
    private var current: String? = null
    private var recentlyEnded: Boolean = false

    @Synchronized fun started(file: String): Boolean {
        if (!valid(file) || identity(file) in ended) return false
        if (current == file) return false
        if (current != null) return false
        current = file
        recentlyEnded = false
        return true
    }

    /** An authenticated status snapshot supersedes a missed END/START wake.
     * Retire the previous identity so delayed wakes cannot resurrect it.
     */
    @Synchronized fun reconcileRecording(file: String): Boolean {
        if (!valid(file) || identity(file) in ended) return false
        if (current == file) return false
        current?.let { previous ->
            ended.add(identity(previous))
            while (ended.size > 64) ended.remove(ended.first())
        }
        current = file
        recentlyEnded = false
        return true
    }

    /** An authenticated PAUSED snapshot supersedes a missed END wake. */
    @Synchronized fun reconcilePaused() {
        current?.let { previous ->
            ended.add(identity(previous))
            while (ended.size > 64) ended.remove(ended.first())
        }
        current = null
        recentlyEnded = false
    }

    @Synchronized fun ended(file: String): Boolean {
        if (!valid(file)) return false
        // A stale END for another recording must not poison a future
        // STARTED event for that identity while this capture is active.
        if (current != null && identity(current!!) != identity(file)) return false
        ended.add(identity(file))
        while (ended.size > 64) ended.remove(ended.first())
        if (current == null) return false
        current = null
        recentlyEnded = true
        return true
    }

    /** Pause closes the current file; Resume must accept a fresh STARTED file. */
    @Synchronized fun pausedByUser(): Boolean {
        val file = current ?: return if (recentlyEnded) {
            recentlyEnded = false
            true
        } else false
        ended.add(identity(file))
        while (ended.size > 64) ended.remove(ended.first())
        current = null
        recentlyEnded = false
        return true
    }

    /** A confirmed user stop ends the current generation even if END is delayed. */
    @Synchronized fun stoppedByUser(): Boolean {
        recentlyEnded = false
        val file = current ?: return false
        ended.add(identity(file))
        while (ended.size > 64) ended.remove(ended.first())
        current = null
        return true
    }

    @Synchronized fun reset() {
        current = null
        recentlyEnded = false
        ended.clear()
    }

    @Synchronized fun isActive(): Boolean = current != null

    private fun identity(file: String): String =
        file.removePrefix("vrec_rec_").substringBefore("_").substringBefore(".")

    private fun valid(file: String): Boolean =
        file.startsWith("vrec_rec_") && file.endsWith(".dat") &&
            file.none { it == '/' || it == '\\' } && file.length <= 200
}
