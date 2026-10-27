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
)

object CallMetadataResolver {
    private val placeholders = setOf(
        "unknown", "unknown number", "private number", "no caller id", "restricted",
        "withheld", "phone", "incoming call", "outgoing call", "ongoing call",
        "call in progress", "voice call", "video call", "calling", "connecting",
        "ringing", "call", "whatsapp", "whatsapp business", "telegram",
        "signal", "messenger", "viber", "end call", "answer", "decline",
    )

    private val privateLabels = setOf(
        "private number", "private caller", "no caller id", "withheld",
        "restricted", "anonymous", "unknown caller id",
    )

    fun privateCallerLabel(text: String?): Boolean =
        text != null && normalize(text) in privateLabels

    fun cleanName(text: String?): String? {
        val value = text?.trim()?.replace(Regex("\\s+"), " ")?.take(120) ?: return null
        if (value.length < 2 || normalize(value) in placeholders) return null
        if (directNumber(value) != null) return null
        // Do not accept descriptive notification text as a caller identity.
        if (value.contains("\n") || value.contains(": ") || value.contains(" • ")) return null
        return value
    }

    fun directNumber(text: String?): String? {
        val raw = text?.trim()?.removePrefix("tel:")?.take(80) ?: return null
        if (!Regex("^\\+?[0-9() .-]+$").matches(raw)) return null
        val digits = raw.count(Char::isDigit)
        return raw.takeIf { digits in 5..18 }
    }

    fun fromNotification(context: Context, notification: Notification): CallerMetadata? {
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
            compat?.getCharSequence("name")?.toString()) +
            people.flatMap { listOf(it.uri?.removePrefix("tel:"), it.name?.toString()) } +
            listOf(title, text, bigText)
        val number = numbers.firstNotNullOfOrNull(::directNumber)
        val name = names.firstNotNullOfOrNull(::cleanName)
            ?: if (number == null && names.any(::privateCallerLabel)) "Private number" else null
        if (number == null && name == null) return null
        return resolve(context, CallerMetadata(name, number, "notification"))
    }

    /** In-app Accessibility labels are accepted only if an exact unique contact or explicit number matches. */
    fun fromVisibleLabels(context: Context, labels: Collection<String>): CallerMetadata? {
        // Ambiguous windows are ignored. An active call does not make arbitrary chat
        // participants or visible numbers safe to attach to its identity.
        val numbers = labels.mapNotNull(::directNumber)
            .distinctBy { it.filter(Char::isDigit) }
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
