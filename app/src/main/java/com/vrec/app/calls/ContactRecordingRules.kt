package com.vrec.app.calls

import android.content.Context
import com.vrec.app.Prefs

/** Selective capture is opt-in. An unknown number can never match an allow-list. */
object ContactRecordingRules {
    private const val ENABLED = "selective_contacts_enabled"
    private const val NUMBERS = "selective_contacts_numbers"

    fun enabled(context: Context) = Prefs.of(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) =
        Prefs.of(context).edit().putBoolean(ENABLED, value).apply()
    fun numbers(context: Context): String = Prefs.of(context).getString(NUMBERS, "").orEmpty()
    fun setNumbers(context: Context, value: String) =
        Prefs.of(context).edit().putString(NUMBERS, value.take(4000)).apply()

    fun normalized(number: String): String = number.filter(Char::isDigit)
    fun matches(number: String?, rules: String): Boolean {
        val candidate = number?.let(::normalized)?.takeIf { it.length >= 7 } ?: return false
        return rules.split(',', '\n', ';').map(::normalized).any { entry ->
            entry.length >= 7 && (entry == candidate ||
                // Country prefixes may differ between contact and Telecom, but
                // require at least 9 trailing digits to reduce false matches.
                (entry.length >= 9 && candidate.length >= 9 &&
                    entry.takeLast(9) == candidate.takeLast(9)))
        }
    }
}
