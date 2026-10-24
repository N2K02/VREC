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
        val number = listOf(uriNumber, person?.name?.toString(), compat?.getCharSequence("name")?.toString(),
            title, extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .firstNotNullOfOrNull(::directNumber)
        val name = listOf(person?.name?.toString(), compat?.getCharSequence("name")?.toString(),
            title).firstNotNullOfOrNull(::cleanName)
        if (number == null && name == null) return null
        return resolve(context, CallerMetadata(name, number, "notification"))
    }

    /** In-app Accessibility labels are accepted only if an exact unique contact or explicit number matches. */
    fun fromVisibleLabels(context: Context, labels: Collection<String>): CallerMetadata? {
        for (text in labels.take(16)) {
            val number = directNumber(text)
            if (number != null) return resolve(context, CallerMetadata(number = number, source = "accessibility"))
        }
        for (text in labels.take(16)) {
            val name = cleanName(text) ?: continue
            val number = uniqueContactNumber(context, name) ?: continue
            return CallerMetadata(name, number, "accessibility", inferredNumber = true)
        }
        return null
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
