package com.vrec.app.calls

import android.content.Context
import com.vrec.app.recording.CallDirection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Call-scoped metadata for shell recordings, which arrive asynchronously after a call.
 * Only a unique time-matched VoIP session may enrich a shell file. Never guess a caller.
 */
object ShellCallMetadataJournal {
    data class Match(val name: String?, val number: String?, val numberSource: String?, val packageName: String?)
    private fun file(context: Context) = File(context.filesDir, "shell_call_metadata.json")
    internal const val WINDOW_MS = 90_000L
    internal fun matches(startedAt: Long, observedAt: Long, recordedPackage: String?, candidatePackage: String?): Boolean =
        kotlin.math.abs(observedAt - startedAt) <= WINDOW_MS &&
            (recordedPackage.isNullOrBlank() || recordedPackage == candidatePackage)
    private const val RETENTION_MS = 48L * 60 * 60 * 1000

    @Synchronized fun remember(context: Context, key: String, startedAt: Long,
                               direction: CallDirection, appPackage: String?,
                               name: String?, number: String?, numberSource: String?) {
        if (direction != CallDirection.VOIP || startedAt <= 0L ||
            (name.isNullOrBlank() && number.isNullOrBlank())) return
        val entries = load(context)
        val now = System.currentTimeMillis()
        val filtered = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { now - it.optLong("startedAt") in 0..RETENTION_MS && it.optString("key") != key }
        val result = JSONArray()
        filtered.takeLast(100).forEach { result.put(it) }
        result.put(JSONObject().put("key", key).put("startedAt", startedAt)
            .put("appPackage", appPackage ?: "").put("name", name ?: "")
            .put("number", number ?: "").put("numberSource", numberSource ?: ""))
        runCatching { file(context).writeText(result.toString()) }
    }

    @Synchronized fun find(context: Context, startedAt: Long, appPackage: String?): Match? {
        val matches = load(context).let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }.filter {
            matches(startedAt, it.optLong("startedAt"), appPackage, it.optString("appPackage"))
        }
        if (matches.size != 1) return null
        val entry = matches.single()
        return Match(entry.optString("name").ifBlank { null },
            entry.optString("number").ifBlank { null },
            entry.optString("numberSource").ifBlank { null },
            entry.optString("appPackage").ifBlank { null })
    }

    private fun load(context: Context): JSONArray =
        runCatching { JSONArray(file(context).readText()) }.getOrDefault(JSONArray())
}
