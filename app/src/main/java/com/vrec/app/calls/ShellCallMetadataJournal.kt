package com.vrec.app.calls

import android.content.Context
import android.util.AtomicFile
import com.vrec.app.recording.CallDirection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Call-scoped metadata for shell recordings, which arrive asynchronously after a call.
 * Only a unique time-matched VoIP session may enrich a shell file. Never guess a caller.
 */
object ShellCallMetadataJournal {
    data class Match(
        val name: String?,
        val number: String?,
        val numberSource: String?,
        val packageName: String?,
        val flowDirection: String?,
    )
    private fun file(context: Context) = File(context.filesDir, "shell_call_metadata.json")
    internal const val WINDOW_MS = 90_000L
    internal fun matches(startedAt: Long, observedAt: Long, recordedPackage: String?, candidatePackage: String?): Boolean =
        kotlin.math.abs(observedAt - startedAt) <= WINDOW_MS &&
            // Never attach another application's caller identity when the
            // recording has no verified VoIP package. A unique timestamp
            // alone is not sufficient during call waiting.
            (!recordedPackage.isNullOrBlank() && recordedPackage == candidatePackage)
    private const val RETENTION_MS = 48L * 60 * 60 * 1000

    @Synchronized fun remember(context: Context, key: String, startedAt: Long,
                               direction: CallDirection, appPackage: String?,
                               name: String?, number: String?, numberSource: String?,
                               flowDirection: CallDirection = CallDirection.UNKNOWN) {
        if (direction != CallDirection.VOIP || startedAt <= 0L ||
            (name.isNullOrBlank() && number.isNullOrBlank() &&
                flowDirection == CallDirection.UNKNOWN)) return
        val entries = load(context)
        val now = System.currentTimeMillis()
        val existing = (0 until entries.length()).map { entries.getJSONObject(it) }
            .firstOrNull { it.optString("key") == key }
        val filtered = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { now - it.optLong("startedAt") in 0..RETENTION_MS && it.optString("key") != key }
        val result = JSONArray()
        filtered.takeLast(100).forEach { result.put(it) }
        fun preferred(value: String?, old: String?): String = value?.takeIf { it.isNotBlank() } ?: old.orEmpty()
        result.put(JSONObject().put("key", key).put("startedAt", startedAt)
            .put("appPackage", preferred(appPackage, existing?.optString("appPackage")))
            .put("name", preferred(name, existing?.optString("name")))
            .put("number", preferred(number, existing?.optString("number")))
            .put("numberSource", preferred(numberSource, existing?.optString("numberSource")))
            .put("flowDirection", preferred(
                flowDirection.name.takeIf { it != CallDirection.UNKNOWN.name },
                existing?.optString("flowDirection"))))
        save(context, result)
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
            entry.optString("appPackage").ifBlank { null },
            entry.optString("flowDirection").takeIf {
                it == CallDirection.INCOMING.name || it == CallDirection.OUTGOING.name
            })
    }

    @Synchronized fun consume(context: Context, startedAt: Long, appPackage: String?) {
        val entries = load(context)
        val matched = (0 until entries.length()).map { entries.getJSONObject(it) }.filter {
            matches(startedAt, it.optLong("startedAt"), appPackage, it.optString("appPackage"))
        }
        if (matched.size != 1) return
        val key = matched.single().optString("key")
        val remaining = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (entry.optString("key") != key) remaining.put(entry)
        }
        save(context, remaining)
    }

    private fun save(context: Context, entries: JSONArray) {
        val atomic = AtomicFile(file(context))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(entries.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            if (stream != null) atomic.failWrite(stream)
            com.vrec.app.Log.w("Could not persist call metadata journal", failure)
        }
    }

    private fun load(context: Context): JSONArray = runCatching {
        JSONArray(AtomicFile(file(context)).openRead().bufferedReader().use { it.readText() })
    }.getOrDefault(JSONArray())
}
