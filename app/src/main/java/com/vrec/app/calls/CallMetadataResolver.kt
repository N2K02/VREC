package com.vrec.app.calls

import android.Manifest
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import java.text.Normalizer
import java.util.Locale

/** Metadata is never a call-state signal. Inferred numbers are marked separately from direct ones. */
data class CallerMetadata(
    val name: String? = null,
    val number: String? = null,
    val source: String = "unknown",
    val inferredNumber: Boolean = false,
    val originalCallerName: String? = null,
    val phoneNumbers: List<String> = emptyList(),
)

object CallMetadataResolver {
    private val placeholders = setOf(
        "unknown", "unknown number", "private number", "no caller id", "restricted",
        "withheld", "phone", "incoming call", "outgoing call", "ongoing call",
        "call in progress", "voice call", "video call", "calling", "connecting",
        "ringing", "call", "whatsapp", "whatsapp business", "telegram",
        "signal", "messenger", "viber", "end call", "answer", "decline",
    )

    /** Call-state text is not a contact, even when it appears in EXTRA_TITLE. */
    private val genericVoipNarrative = Regex(
        "^(?:(?:whatsapp(?: business)?|telegram|signal|messenger|viber)\\s+)?(?:(?:incoming|outgoing|ongoing|missed)\\s+)?(?:(?:group|conference)\\s+)?(?:(?:voice|video|audio)\\s+)?(?:call|calling|ringing)(?:\\s+(?:in progress|ongoing))?$"
    )
    private val genericDirectionNarrative = Regex(
        "^(?:incoming|outgoing|ongoing|missed)(?:\\s+(?:voice|video|audio))?\\s+call(?:\\s+(?:from|to|with)\\s+.+)?$"
    )
    private val genericCallAction = Regex("^(?:calling|dialing|ringing)(?:[\\s…:.]+.*)?$")

    private val privateLabels = setOf(
        "private number", "private caller", "no caller id", "withheld",
        "restricted", "anonymous", "unknown caller id", "private", "number withheld",
        "hidden number", "blocked", "numéro masqué", "numero privato", "número privado",
    )

    fun privateCallerLabel(text: String?): Boolean =
        text != null && normalize(cleanLabel(text).orEmpty()) in privateLabels

    fun cleanLabel(text: String?): String? {
        if (text == null) return null
        return text.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            .replace(Regex("[\\s\\p{Zs}]+"), " ").trim()
    }

    fun cleanName(text: String?): String? {
        val value = cleanLabel(text)?.take(120) ?: return null
        if (value.length < 2 || normalize(value) in placeholders) return null
        if (directNumber(value) != null) return null
        // Do not accept descriptive notification text as a caller identity.
        if (value.contains("\n") || value.contains(": ") || value.contains(" • ")) return null
        return value
    }

    /** App call titles can be nicknames or group names, including punctuation. */
    fun cleanAppName(text: String?): String? = cleanLabel(text)?.take(256)?.takeIf {
        val normalized = normalize(it)
        it.isNotBlank() && normalized !in placeholders &&
            !genericVoipNarrative.matches(normalized) &&
            !genericDirectionNarrative.matches(normalized) &&
            !genericCallAction.matches(normalized) &&
            directNumber(it) == null
    }

    fun directNumber(text: String?): String? {
        val raw = cleanLabel(text)?.removePrefix("tel:")?.take(80) ?: return null
        if (!Regex("^\\+?[0-9() .-]+$").matches(raw)) return null
        val digits = raw.count(Char::isDigit)
        return raw.takeIf { digits in 5..18 }
    }

    fun fromNotification(context: Context, notification: Notification, thirdParty: Boolean = false): CallerMetadata? {
        val extras = notification.extras ?: return null
        val person: Person? = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
            else {
                @Suppress("DEPRECATION")
                extras.getParcelable< Person >(Notification.EXTRA_CALL_PERSON)
            }
        }.getOrNull()
        val compat = extras.getBundle("android.callPersonCompat")
        val uriNumber = person?.uri?.takeIf { it.startsWith("tel:", true) }
            ?.substring(4)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val people = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                if (Build.VERSION.SDK_INT >= 33)
                    extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
                else {
                    @Suppress("DEPRECATION")
                    extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
                }
            } else null
        }.getOrNull().orEmpty()
        val names = listOf(person?.name?.toString(), compat?.getCharSequence("name")?.toString()) +
            people.map { it.name?.toString() } + listOf(title, text, bigText)
        val numbers = listOf(uriNumber, person?.name?.toString(),
            compat?.getString("uri")?.takeIf { it.startsWith("tel:", true) },
            compat?.getCharSequence("name")?.toString()) +
            people.flatMap { listOf(it.uri?.removePrefix("tel:"), it.name?.toString()) } +
            listOf(title, text, bigText)
        val directNumbers = VoipIdentityPolicy.numbers(numbers.filterNotNull())
        val number = if (thirdParty) directNumbers.singleOrNull() else directNumbers.firstOrNull()
        val appNames = listOf(title, person?.name?.toString(), compat?.getCharSequence("name")?.toString()) +
            people.map { it.name?.toString() }
        val name = (if (thirdParty) appNames.firstNotNullOfOrNull(::cleanAppName)
            else names.firstNotNullOfOrNull(::cleanName))
            ?: if (number == null && names.any(::privateCallerLabel)) "Private number" else null
        if (directNumbers.isEmpty() && name == null) return null
        val metadata = CallerMetadata(name, number, "notification",
            originalCallerName = name.takeIf { thirdParty }, phoneNumbers = directNumbers)
        return if (thirdParty) metadata else resolve(context, metadata)
    }

    /** The caller gates labels to an active call screen; third-party names are never contact-matched. */
    fun fromVisibleLabels(context: Context, labels: Collection<String>, thirdParty: Boolean = false): CallerMetadata? {
        // Cellular identity remains conservative. A verified third-party call screen
        // can expose several explicit participant numbers, never inferred nicknames.
        val numbers = labels.mapNotNull(::directNumber)
            .distinctBy { it.filter(Char::isDigit) }
        if (thirdParty) {
            val name = VoipAccessibilityCallerPolicy.uniqueCallerName(labels)
            if (name == null && numbers.isEmpty()) return null
            return CallerMetadata(name = name, number = numbers.singleOrNull(),
                source = "accessibility", originalCallerName = name, phoneNumbers = numbers)
        }
        if (numbers.size == 1) {
            return resolve(context, CallerMetadata(number = numbers.single(), source = "accessibility"))
        }
        if (numbers.size > 1) return null
        val matches = labels.mapNotNull { text ->
            val name = cleanName(text) ?: return@mapNotNull null
            val number = uniqueContactNumber(context, name) ?: return@mapNotNull null
            name to number
        }.distinctBy { it.second.filter(Char::isDigit) }
        if (matches.size != 1) return null
        return CallerMetadata(matches.single().first, matches.single().second,
            "accessibility", inferredNumber = true)
    }

    fun resolve(context: Context, candidate: CallerMetadata): CallerMetadata {
        if (candidate.number != null || candidate.name == null) return candidate
        val inferred = uniqueContactNumber(context, candidate.name) ?: return candidate
        return candidate.copy(number = inferred, inferredNumber = true)
    }

    /** Exact full-name match only; ambiguous multi-number contacts must stay unknown. */
    fun uniqueContactNumber(context: Context, name: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val target = normalize(name)
        if (target.length < 2 || target in placeholders) return null
        val results = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (normalize(cursor.getString(0) ?: "") == target) {
                        val number = cursor.getString(1) ?: continue
                        if (number.count(Char::isDigit) >= 5) results.add(number)
                    }
                }
            }
        }
        return results.distinctBy { it.filter(Char::isDigit) }.singleOrNull()
    }

    internal fun normalize(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}
