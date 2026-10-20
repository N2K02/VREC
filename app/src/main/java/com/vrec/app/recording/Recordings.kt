package com.vrec.app.recording

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CallDirection { INCOMING, OUTGOING, VOIP, UNKNOWN }

/** One saved recording plus the little we know about the call it came from. */
data class Recording(
    val file: File,
    val startedAt: Long,
    val direction: CallDirection,
    /** Phone number or contact name, or app name for VoIP, when known. */
    val peer: String?,
    val sourceLabel: String?,
    val silent: Boolean,
    val durationMs: Long,
) {
    val sizeBytes: Long get() = file.length()
}

/**
 * Recordings live in the app's own external files folder (no storage permission needed) with a
 * small `.json` sidecar beside each one for the call details. Share or export from the list.
 */
object RecordingStore {

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    fun newFile(context: Context): File =
        File(dir(context), "${stamp.format(Date())}.m4a")

    fun writeMeta(
        file: File,
        direction: CallDirection,
        peer: String?,
        sourceLabel: String?,
        silent: Boolean,
        durationMs: Long,
    ) {
        val json = JSONObject()
            .put("startedAt", System.currentTimeMillis())
            .put("direction", direction.name)
            .put("peer", peer ?: JSONObject.NULL)
            .put("source", sourceLabel ?: JSONObject.NULL)
            .put("silent", silent)
            .put("durationMs", durationMs)
        runCatching { sidecar(file).writeText(json.toString()) }
    }

    fun list(context: Context): List<Recording> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".m4a") }
            ?.map { read(it) }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    fun delete(recording: Recording) {
        runCatching { recording.file.delete() }
        runCatching { sidecar(recording.file).delete() }
    }

    private fun sidecar(file: File) = File(file.parentFile, file.name + ".json")

    private fun read(file: File): Recording {
        val meta = runCatching { JSONObject(sidecar(file).readText()) }.getOrNull()
        return Recording(
            file = file,
            startedAt = meta?.optLong("startedAt", file.lastModified()) ?: file.lastModified(),
            direction = runCatching { CallDirection.valueOf(meta?.optString("direction") ?: "") }
                .getOrDefault(CallDirection.UNKNOWN),
            peer = meta?.optString("peer")?.takeIf { it.isNotBlank() && it != "null" },
            sourceLabel = meta?.optString("source")?.takeIf { it.isNotBlank() && it != "null" },
            silent = meta?.optBoolean("silent", false) ?: false,
            durationMs = meta?.optLong("durationMs", 0L) ?: 0L,
        )
    }
}
