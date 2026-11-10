package com.vrec.app.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact, predictable labels for playback media controls. No filesystem paths. */
internal object RecordingPlaybackLabels {
    fun details(sourcePackage: String?, direction: String?, startedAt: Long): String {
        val source = when (sourcePackage) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "org.telegram.messenger" -> "Telegram"
            "org.thoughtcrime.securesms" -> "Signal"
            "com.facebook.orca" -> "Messenger"
            "com.viber.voip" -> "Viber"
            "com.skype.raider" -> "Skype"
            "com.google.android.apps.tachyon" -> "Google Meet"
            else -> if (sourcePackage.isNullOrBlank()) "Phone call" else "App call"
        }
        val flow = when (direction?.uppercase(Locale.ROOT)) {
            "INCOMING" -> "Incoming"
            "OUTGOING" -> "Outgoing"
            else -> "Call"
        }
        val date = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault())
            .format(Date(startedAt.coerceAtLeast(0L)))
        return "$source · $flow · $date"
    }
}
