package com.vrec.app.calls

import android.content.Context
import android.Manifest
import android.telephony.SubscriptionManager
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Turns a raw number into a contact name when READ_CONTACTS is granted, else the number itself. */
object CallInfo {


    /** Never attribute SIM/carrier data to a call when multiple SIMs are active.
     * Android does not guarantee the call's account/slot for every detection path.
     * The sole active subscription is unambiguous; everything else stays unknown.
     */
    data class SingleSim(val label: String?, val slot: Int?, val carrier: String?)

    fun singleActiveSim(context: Context): SingleSim? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val manager = context.getSystemService(SubscriptionManager::class.java)
            val info = manager?.activeSubscriptionInfoList?.singleOrNull() ?: return@runCatching null
            SingleSim(
                label = info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                slot = info.simSlotIndex.takeIf { it >= 0 }?.plus(1),
                carrier = info.carrierName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

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
