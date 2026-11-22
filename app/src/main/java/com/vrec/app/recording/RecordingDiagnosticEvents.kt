package com.vrec.app.recording

/**
 * Bounded, in-memory, privacy-safe recorder status history.
 * Store only predetermined event labels; never retain raw log messages,
 * phone numbers, filenames, paths, credentials or audio data.
 */
object RecordingDiagnosticEvents {
    private const val MAX_EVENTS = 100
    private val events = ArrayDeque<String>()

    @Synchronized
    fun record(message: String) {
        val category = classify(message) ?: return
        events.addLast(category)
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = events.toList().asReversed()

    @Synchronized
    fun clear() = events.clear()

    internal fun classify(message: String): String? = when {
        message.contains("shell recording import failed", ignoreCase = true) ->
            "Recording import failed"
        message.contains("saved shell recording", ignoreCase = true) ->
            "Recording imported successfully"
        message.contains("shell cleanup", ignoreCase = true) ->
            "Shell cleanup needs attention"
        message.contains("shell import found", ignoreCase = true) ->
            "Pending recordings detected"
        message.contains("recorder startup failed", ignoreCase = true) ->
            "Recorder startup failed"
        message.contains("ADB: recording helper started", ignoreCase = true) ->
            "ADB recorder helper ready"
        else -> null
    }
}
