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
        val mediaType: String?,
        val direction: CallDirection,
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

    /** Extremely short fallback for shell files mislabeled _in/_out during a
     *  Telecom VoIP call. Competing SIM and VoIP calls fail closed. */
    internal const val MISLABELED_WINDOW_MS = 20_000L

    data class Candidate(
        val key: String,
        val startedAt: Long,
        val direction: CallDirection,
        val packageName: String?,
        val flowDirection: CallDirection,
        val name: String?,
        val number: String?,
        val mediaType: String?,
    )

    internal fun selectCellularTaggedVoip(
        startedAt: Long,
        shellDirection: CallDirection,
        entries: List<Candidate>,
    ): Candidate? {
        if (shellDirection !in setOf(CallDirection.INCOMING, CallDirection.OUTGOING)) return null
        val nearby = entries.filter { candidate ->
            candidate.startedAt > 0L &&
                kotlin.math.abs(candidate.startedAt - startedAt) <= MISLABELED_WINDOW_MS &&
                (candidate.flowDirection == CallDirection.UNKNOWN ||
                    candidate.flowDirection == shellDirection)
        }
        // A second SIM/VoIP call, even with no caller identity, is ambiguous.
        if (nearby.size != 1) return null
        return nearby.single().takeIf {
            it.direction == CallDirection.VOIP &&
                !it.packageName.isNullOrBlank() &&
                (!it.name.isNullOrBlank() || !it.number.isNullOrBlank()) &&
                it.flowDirection == shellDirection
        }
    }

    private fun JSONObject.asCandidate(): Candidate {
        val direction = runCatching {
            CallDirection.valueOf(optString("direction"))
        }.getOrDefault(CallDirection.UNKNOWN)
        val flow = runCatching {
            CallDirection.valueOf(optString("flowDirection"))
        }.getOrDefault(CallDirection.UNKNOWN)
        return Candidate(
            optString("key"), optLong("startedAt"), direction,
            optString("appPackage").ifBlank { null }, flow,
            optString("name").ifBlank { null }, optString("number").ifBlank { null },
            optString("mediaType").takeIf { it == "VIDEO" || it == "VOICE" },
        )
    }

    private fun entries(context: Context) = load(context).let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun JSONObject.asMatch(): Match = Match(
        optString("name").ifBlank { null },
        optString("number").ifBlank { null },
        optString("numberSource").ifBlank { null },
        optString("appPackage").ifBlank { null },
        optString("flowDirection").takeIf {
            it == CallDirection.INCOMING.name || it == CallDirection.OUTGOING.name
        },
        optString("mediaType").takeIf { it == "VIDEO" || it == "VOICE" },
        runCatching { CallDirection.valueOf(optString("direction")) }
            .getOrDefault(CallDirection.UNKNOWN),
    )

    /** Called only for a generic shell filename; legacy journal entries are
     *  excluded because they did not record competing cellular sessions. */
    @Synchronized fun findForCellularTaggedVoip(
        context: Context, startedAt: Long, direction: CallDirection,
    ): Match? {
        val candidates = entries(context).filter { it.optInt("version") >= 2 }
        val selected = selectCellularTaggedVoip(
            startedAt, direction, candidates.map { it.asCandidate() }) ?: return null
        return candidates.firstOrNull { it.optString("key") == selected.key }?.asMatch()
    }

    @Synchronized fun remember(context: Context, key: String, startedAt: Long,
                               direction: CallDirection, appPackage: String?,
                               name: String?, number: String?, numberSource: String?,
                               flowDirection: CallDirection = CallDirection.UNKNOWN,
                               mediaType: String? = null) {
        if (direction !in setOf(CallDirection.VOIP, CallDirection.INCOMING,
                CallDirection.OUTGOING) || startedAt <= 0L) return
        val entries = load(context)
        val now = System.currentTimeMillis()
        val existing = (0 until entries.length()).map { entries.getJSONObject(it) }
            .firstOrNull { it.optString("key") == key }
        val filtered = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { now - it.optLong("startedAt") in 0..RETENTION_MS && it.optString("key") != key }
        val result = JSONArray()
        filtered.takeLast(100).forEach { result.put(it) }
        fun preferred(value: String?, old: String?): String = value?.takeIf { it.isNotBlank() } ?: old.orEmpty()
        result.put(JSONObject().put("version", 2).put("key", key)
            .put("startedAt", startedAt).put("direction", direction.name)
            .put("appPackage", preferred(appPackage, existing?.optString("appPackage")))
            .put("name", preferred(name, existing?.optString("name")))
            .put("number", preferred(number, existing?.optString("number")))
            .put("numberSource", preferred(numberSource, existing?.optString("numberSource")))
            .put("flowDirection", preferred(
                flowDirection.name.takeIf { it != CallDirection.UNKNOWN.name },
                existing?.optString("flowDirection")))
            .put("mediaType", if (mediaType == "VIDEO") "VIDEO"
                else preferred(mediaType, existing?.optString("mediaType"))))
        save(context, result)
    }

    @Synchronized fun find(context: Context, startedAt: Long, appPackage: String?): Match? {
        val matches = entries(context).filter {
            matches(startedAt, it.optLong("startedAt"), appPackage, it.optString("appPackage"))
        }
        if (matches.size != 1) return null
        return matches.single().asMatch()
    }

    /** An adopted notification identity is superseded by Telecom for the same call. */
    @Synchronized fun forget(context: Context, key: String) {
        val saved = entries(context)
        val filtered = saved.filter { it.optString("key") != key }
        if (filtered.size == saved.size) return
        val result = JSONArray()
        filtered.forEach { result.put(it) }
        save(context, result)
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
