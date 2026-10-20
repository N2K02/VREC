package com.vrec.app.recording

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CallDirection { INCOMING, OUTGOING, VOIP, UNKNOWN }

/** One saved recording and its editable, backwards-compatible sidecar metadata. */
data class Recording(
    val file: File,
    val startedAt: Long,
    val direction: CallDirection,
    /** Number, contact name, or VoIP app name supplied by call detection. */
    val peer: String?,
    val sourceLabel: String?,
    val silent: Boolean,
    val durationMs: Long,
    val phoneNumber: String? = null,
    val contactName: String? = null,
    val countryCode: String? = null,
    val simLabel: String? = null,
    val simSlot: Int? = null,
    val carrier: String? = null,
    val callType: String? = null,
    val notes: String = "",
    val favorite: Boolean = false,
    val bookmarksMs: List<Long> = emptyList(),
    val endedAt: Long? = null,
    val metadataAvailable: Boolean = true,
) {
    val sizeBytes: Long get() = file.length()
    val displayName: String get() = contactName?.takeIf { it.isNotBlank() }
        ?: peer?.takeIf { it.isNotBlank() }
        ?: phoneNumber?.takeIf { it.isNotBlank() }
        ?: file.nameWithoutExtension
}

/**
 * Audio stays in app-specific storage as M4A; editable details live in a JSON sidecar.
 * Old sidecars and recordings without metadata remain readable.
 */
object RecordingStore {
    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    fun newFile(context: Context, direction: CallDirection = CallDirection.UNKNOWN, peer: String? = null): File {
        val tag = when (direction) {
            CallDirection.INCOMING -> "IN"
            CallDirection.OUTGOING -> "OUT"
            CallDirection.VOIP -> "VOIP"
            CallDirection.UNKNOWN -> null
        }
        val who = peer?.replace(Regex("[^A-Za-z0-9+._-]+"), "_")?.trim('_')?.take(40)?.takeIf { it.isNotBlank() }
        val name = listOfNotNull(stamp.format(Date()), tag, who).joinToString("_")
        var file = File(dir(context), "$name.m4a")
        var suffix = 1
        while (file.exists()) file = File(dir(context), "${name}_${suffix++}.m4a")
        return file
    }

    fun writeMeta(
        file: File,
        direction: CallDirection,
        peer: String?,
        sourceLabel: String?,
        silent: Boolean,
        durationMs: Long,
        phoneNumber: String? = extractPhone(peer),
        contactName: String? = peer?.takeIf { phoneNumber != null && it != phoneNumber },
    ) {
        val started = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("startedAt", started)
            .put("endedAt", System.currentTimeMillis())
            .put("direction", direction.name)
            .put("peer", peer ?: JSONObject.NULL)
            .put("phoneNumber", phoneNumber ?: JSONObject.NULL)
            .put("contactName", contactName ?: JSONObject.NULL)
            .put("countryCode", inferCountryCode(phoneNumber) ?: JSONObject.NULL)
            .put("simLabel", JSONObject.NULL)
            .put("simSlot", JSONObject.NULL)
            .put("carrier", JSONObject.NULL)
            .put("callType", direction.name)
            .put("source", sourceLabel ?: JSONObject.NULL)
            .put("silent", silent)
            .put("durationMs", durationMs)
            .put("notes", "")
            .put("favorite", false)
            .put("bookmarksMs", JSONArray())
        runCatching { sidecar(file).writeText(json.toString()) }
    }

    fun updateDetails(
        recording: Recording,
        notes: String,
        favorite: Boolean,
        contactName: String? = recording.contactName,
        phoneNumber: String? = recording.phoneNumber,
        countryCode: String? = recording.countryCode,
        simLabel: String? = recording.simLabel,
        carrier: String? = recording.carrier,
    ) {
        val json = readJson(recording.file) ?: JSONObject()
            .put("startedAt", recording.startedAt)
            .put("direction", recording.direction.name)
            .put("peer", recording.peer ?: JSONObject.NULL)
            .put("source", recording.sourceLabel ?: JSONObject.NULL)
            .put("silent", recording.silent)
            .put("durationMs", recording.durationMs)
        json.put("schemaVersion", 2)
            .put("notes", notes.take(2000))
            .put("favorite", favorite)
            .put("contactName", contactName?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("phoneNumber", phoneNumber?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("countryCode", countryCode?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("simLabel", simLabel?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("carrier", carrier?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        runCatching { sidecar(recording.file).writeText(json.toString()) }
    }

    fun addBookmark(recording: Recording, positionMs: Long) {
        val json = readJson(recording.file) ?: return
        val old = json.optJSONArray("bookmarksMs") ?: JSONArray()
        if ((0 until old.length()).none { kotlin.math.abs(old.optLong(it) - positionMs) < 1500 }) {
            old.put(positionMs.coerceAtLeast(0))
        }
        json.put("bookmarksMs", old)
        runCatching { sidecar(recording.file).writeText(json.toString()) }
    }

    fun list(context: Context): List<Recording> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".m4a", ignoreCase = true) }
            ?.map { read(it) }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    fun delete(recording: Recording) {
        runCatching { recording.file.delete() }
        runCatching { sidecar(recording.file).delete() }
    }

    fun exportDetails(recording: Recording): String {
        val json = readJson(recording.file) ?: JSONObject()
        return JSONObject(json.toString())
            .put("fileName", recording.file.name)
            .put("fileSizeBytes", recording.sizeBytes)
            .put("exportedAt", System.currentTimeMillis())
            .toString(2)
    }

    private fun sidecar(file: File) = File(file.parentFile, file.name + ".json")
    private fun readJson(file: File): JSONObject? = runCatching { JSONObject(sidecar(file).readText()) }.getOrNull()

    private fun read(file: File): Recording {
        val meta = readJson(file)
        val peer = meta?.optString("peer")?.takeIf { it.isNotBlank() && it != "null" }
        val phone = meta?.optString("phoneNumber")?.takeIf { it.isNotBlank() && it != "null" }
            ?: extractPhone(peer)
        val bookmarks = meta?.optJSONArray("bookmarksMs")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()
        return Recording(
            file = file,
            startedAt = meta?.optLong("startedAt", file.lastModified()) ?: file.lastModified(),
            direction = runCatching { CallDirection.valueOf(meta?.optString("direction") ?: "") }.getOrDefault(CallDirection.UNKNOWN),
            peer = peer,
            sourceLabel = meta?.optString("source")?.takeIf { it.isNotBlank() && it != "null" },
            silent = meta?.optBoolean("silent", false) ?: false,
            durationMs = meta?.optLong("durationMs", 0L) ?: 0L,
            phoneNumber = phone,
            contactName = meta?.optString("contactName")?.takeIf { it.isNotBlank() && it != "null" },
            countryCode = meta?.optString("countryCode")?.takeIf { it.isNotBlank() && it != "null" } ?: inferCountryCode(phone),
            simLabel = meta?.optString("simLabel")?.takeIf { it.isNotBlank() && it != "null" },
            simSlot = meta?.optInt("simSlot")?.takeIf { it > 0 },
            carrier = meta?.optString("carrier")?.takeIf { it.isNotBlank() && it != "null" },
            callType = meta?.optString("callType")?.takeIf { it.isNotBlank() && it != "null" },
            notes = meta?.optString("notes")?.takeIf { it != "null" } ?: "",
            favorite = meta?.optBoolean("favorite", false) ?: false,
            bookmarksMs = bookmarks,
            endedAt = meta?.optLong("endedAt")?.takeIf { it > 0 },
            metadataAvailable = meta != null,
        )
    }

    private fun extractPhone(value: String?): String? {
        val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleaned = candidate.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        return cleaned.takeIf { it.count(Char::isDigit) >= 5 }
    }

    /** Only infer a country calling code from an explicit international number, never device location. */
    private fun inferCountryCode(number: String?): String? {
        val n = number?.trim()?.replace(" ", "") ?: return null
        if (!n.startsWith("+")) return null
        return when {
            n.startsWith("+230") -> "MU (+230)"
            n.startsWith("+44") -> "GB (+44)"
            n.startsWith("+1") -> "US/CA (+1)"
            n.startsWith("+91") -> "IN (+91)"
            n.startsWith("+27") -> "ZA (+27)"
            n.startsWith("+61") -> "AU (+61)"
            n.startsWith("+33") -> "FR (+33)"
            n.startsWith("+49") -> "DE (+49)"
            n.startsWith("+86") -> "CN (+86)"
            n.startsWith("+81") -> "JP (+81)"
            n.startsWith("+971") -> "AE (+971)"
            else -> "International number"
        }
    }
}
