package com.vrec.app.calls

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Turns a raw number into a contact name when READ_CONTACTS is granted, else the number itself. */
object CallInfo {

    fun describe(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        return lookupName(context, number) ?: number
    }

    private fun lookupName(context: Context, number: String): String? {
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
