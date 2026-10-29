package com.vrec.app.recording

import android.content.Context
import android.util.AtomicFile
import com.vrec.app.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class CallDirection { INCOMING, OUTGOING, VOIP, UNKNOWN }

/** The audio capture path that actually produced a saved file, not the selected UI mode. */
enum class RecordingType { STANDARD, ADB }

object RecordingTypeResolver {
    /**
     * Older ADB imports used the dedicated vrec_rec_* filename and a shell source label.
     * Never infer capture mode from the currently selected recording method.
     */
    fun resolve(savedType: String?, sourceLabel: String?, fileName: String): RecordingType? {
        when (savedType?.uppercase(Locale.ROOT)) {
            RecordingType.ADB.name -> return RecordingType.ADB
            RecordingType.STANDARD.name -> return RecordingType.STANDARD
        }
        if (sourceLabel == "Enhanced shell capture" || sourceLabel == "Shell VOICE_CALL" ||
            (Regex("^vrec_rec_\\d{12,15}_(?:in|out)\\.ogg$", RegexOption.IGNORE_CASE)
                .matches(fileName))) return RecordingType.ADB
        return if (sourceLabel != null || fileName.endsWith(".m4a", true)) RecordingType.STANDARD
            else null
    }
}

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
    val callFlowDirection: String? = null,
    val sourcePackage: String? = null,
    val recordingType: RecordingType? = null,
    val audioHealth: String? = null,
    val audioHealthWarning: Boolean = false,
    val notes: String = "",
    val favorite: Boolean = false,
    val bookmarksMs: List<Long> = emptyList(),
    val endedAt: Long? = null,
    val callConnectedAt: Long? = null,
    val callEndedAt: Long? = null,
    val metadataAvailable: Boolean = true,
    /** CALL media, not video file capture: VIDEO or VOICE when observed. */
    val callMediaType: String? = null,
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
    /** Notifies visible recording lists when a completed file is available. */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    fun notifySaved() {
        revision.value = revision.value + 1L
    }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val metadataLock = Any()

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
        callConnectedAt: Long? = null,
        callEndedAt: Long? = null,
        appPackage: String? = null,
        accountId: String? = null,
        numberSource: String? = null,
        callFlowDirection: String? = null,
        simLabel: String? = null,
        simSlot: Int? = null,
        carrier: String? = null,
        recordingType: RecordingType = RecordingType.STANDARD,
        startedAtOverride: Long? = null,
        endedAtOverride: Long? = null,
        audioHealth: String? = null,
        callMediaType: String? = null,
    ): Boolean {
        val started = startedAtOverride ?: runCatching { stamp.parse(file.name.take(15))?.time }.getOrNull()
            ?: file.lastModified().takeIf { it > 0 }
            ?: System.currentTimeMillis()
        val json = JSONObject()
            .put("schemaVersion", 3)
            .put("recordingType", recordingType.name)
            .put("callConnectedAt", callConnectedAt ?: JSONObject.NULL)
            .put("callEndedAt", callEndedAt ?: JSONObject.NULL)
            .put("callDurationMs", if (callConnectedAt != null && callEndedAt != null && callEndedAt >= callConnectedAt) callEndedAt - callConnectedAt else JSONObject.NULL)
            .put("callTimingEstimated", true)
            .put("startedAt", started)
            .put("endedAt", endedAtOverride ?: System.currentTimeMillis())
            .put("direction", direction.name)
            .put("peer", peer ?: JSONObject.NULL)
            .put("phoneNumber", phoneNumber ?: JSONObject.NULL)
            .put("contactName", contactName ?: JSONObject.NULL)
            .put("countryCode", inferCountryCode(phoneNumber) ?: JSONObject.NULL)
            .put("simLabel", simLabel ?: JSONObject.NULL)
            .put("simSlot", simSlot ?: JSONObject.NULL)
            .put("carrier", carrier ?: JSONObject.NULL)
            .put("callType", if (direction == CallDirection.VOIP) "VOIP" else direction.name)
            .put("callMediaType", callMediaType?.takeIf {
                it == "VIDEO" || it == "VOICE"
            } ?: JSONObject.NULL)
            .put("sourcePackage", appPackage ?: JSONObject.NULL)
            .put("phoneAccountId", accountId ?: JSONObject.NULL)
            .put("numberSource", numberSource ?: JSONObject.NULL)
            .put("callFlowDirection", callFlowDirection ?: JSONObject.NULL)
            .put("source", sourceLabel ?: JSONObject.NULL)
            .put("silent", silent)
            .put("durationMs", durationMs)
            .put("notes", "")
            .put("favorite", false)
            .put("bookmarksMs", JSONArray())
        if (audioHealth != null) {
            json.put("audioHealth", audioHealth)
                .put("audioHealthWarning", audioHealth.contains("QUIET") ||
                    audioHealth.contains("CLIPPING") || audioHealth.contains("NO_AUDIO"))
        }
        return writeJsonAtomic(file, json)
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
        callFlowDirection: String? = recording.callFlowDirection,
    ): Boolean = synchronized(metadataLock) {
        val json = readJson(recording.file) ?: JSONObject()
            .put("startedAt", recording.startedAt)
            .put("direction", recording.direction.name)
            .put("peer", recording.peer ?: JSONObject.NULL)
            .put("source", recording.sourceLabel ?: JSONObject.NULL)
            .put("silent", recording.silent)
            .put("durationMs", recording.durationMs)
        json.put("schemaVersion", 3)
            .put("notes", notes.take(2000))
            .put("favorite", favorite)
            .put("contactName", contactName?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("phoneNumber", phoneNumber?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("countryCode", countryCode?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("simLabel", simLabel?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("carrier", carrier?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("callFlowDirection", when (callFlowDirection?.uppercase(Locale.ROOT)) {
                "INCOMING" -> "INCOMING"
                "OUTGOING" -> "OUTGOING"
                "UNKNOWN" -> "UNKNOWN"
                else -> JSONObject.NULL
            })
        writeJsonAtomic(recording.file, json)
    }

    fun addBookmark(recording: Recording, positionMs: Long): Boolean = synchronized(metadataLock) {
        val json = readJson(recording.file) ?: return@synchronized false
        val old = json.optJSONArray("bookmarksMs") ?: JSONArray()
        if ((0 until old.length()).none { kotlin.math.abs(old.optLong(it) - positionMs) < 1500 }) {
            old.put(positionMs.coerceAtLeast(0))
        }
        json.put("bookmarksMs", old)
        writeJsonAtomic(recording.file, json)
    }

    fun list(context: Context): List<Recording> =
        dir(context).listFiles { f -> f.isFile && (f.name.endsWith(".m4a", ignoreCase = true) || f.name.endsWith(".ogg", ignoreCase = true)) }
            ?.map { read(it) }
            ?.sortedByDescending { it.startedAt }
            ?: emptyList()

    /** Keep the metadata if audio deletion fails; report partial deletion. */
    fun delete(recording: Recording): Boolean {
        val audioDeleted = !recording.file.exists() || recording.file.delete()
        if (!audioDeleted) {
            Log.w("Recording deletion failed: audio is still present")
            return false
        }
        val metadata = sidecar(recording.file)
        val metadataDeleted = runCatching {
            // Also removes AtomicFile's .new/.bak recovery files.
            AtomicFile(metadata).delete()
            !metadata.exists() && !File(metadata.path + ".new").exists() &&
                !File(metadata.path + ".bak").exists()
        }.getOrDefault(false)
        if (!metadataDeleted) Log.w("Recording deletion partially failed: metadata is still present")
        return metadataDeleted
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

    /** AtomicFile retains the previous complete JSON if a write fails. */
    private fun writeJsonAtomic(file: File, json: JSONObject): Boolean = synchronized(metadataLock) {
        val atomic = AtomicFile(sidecar(file))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (t: Exception) {
            if (stream != null) runCatching { atomic.failWrite(stream) }
            Log.w("Recording metadata could not be saved", t)
            false
        }
    }

    private fun readJson(file: File): JSONObject? = synchronized(metadataLock) {
        runCatching {
            AtomicFile(sidecar(file)).openRead().bufferedReader(Charsets.UTF_8)
                .use { JSONObject(it.readText()) }
        }.getOrNull()
    }

    /** AtomicFile recovers the previous completed state on interrupted writes. */
    fun hasValidMetadata(file: File): Boolean =
        readJson(file)?.optInt("schemaVersion", 0) == 3

    private fun read(file: File): Recording {
        val meta = readJson(file)
        // Our own yyyyMMdd_HHmmss_(IN|OUT|VOIP) filename is reliable for the
        // recorded start time and direction even before JSON sidecars existed.
        val filenameInfo = Regex("^([0-9]{8}_[0-9]{6})(?:_(IN|OUT|VOIP))?(?:_|\\.)")
            .find(file.name)
        val filenameStartedAt = filenameInfo?.groupValues?.get(1)?.let { value ->
            runCatching {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .apply { isLenient = false }.parse(value)?.time
            }.getOrNull()
        }
        val filenameDirection = when (filenameInfo?.groupValues?.getOrNull(2)) {
            "IN" -> CallDirection.INCOMING
            "OUT" -> CallDirection.OUTGOING
            "VOIP" -> CallDirection.VOIP
            else -> CallDirection.UNKNOWN
        }
        val peer = meta?.optString("peer")?.takeIf { it.isNotBlank() && it != "null" }
        val phone = meta?.optString("phoneNumber")?.takeIf { it.isNotBlank() && it != "null" }
            ?: extractPhone(peer)
        val bookmarks = meta?.optJSONArray("bookmarksMs")?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: emptyList()
        return Recording(
            file = file,
            startedAt = meta?.optLong("startedAt")?.takeIf { it > 0 }
                ?: filenameStartedAt ?: file.lastModified(),
            direction = if (meta == null) filenameDirection else
                runCatching { CallDirection.valueOf(meta.optString("direction")) }
                    .getOrDefault(CallDirection.UNKNOWN),
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
            callFlowDirection = meta?.optString("callFlowDirection")?.takeIf { it.isNotBlank() && it != "null" },
            sourcePackage = meta?.optString("sourcePackage")
                ?.takeIf { it.isNotBlank() && it != "null" },
            recordingType = RecordingTypeResolver.resolve(
                meta?.optString("recordingType")?.takeIf { it.isNotBlank() && it != "null" },
                meta?.optString("source")?.takeIf { it.isNotBlank() && it != "null" },
                file.name,
            ),
            audioHealth = meta?.optString("audioHealth")?.takeIf { it.isNotBlank() && it != "null" },
            audioHealthWarning = meta?.optBoolean("audioHealthWarning", false) ?: false,
            notes = meta?.optString("notes")?.takeIf { it != "null" } ?: "",
            favorite = meta?.optBoolean("favorite", false) ?: false,
            bookmarksMs = bookmarks,
            endedAt = meta?.optLong("endedAt")?.takeIf { it > 0 },
            callConnectedAt = meta?.optLong("callConnectedAt")?.takeIf { it > 0 },
            callEndedAt = meta?.optLong("callEndedAt")?.takeIf { it > 0 },
            metadataAvailable = meta != null,
            callMediaType = meta?.optString("callMediaType")
                ?.takeIf { it == "VIDEO" || it == "VOICE" },
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
