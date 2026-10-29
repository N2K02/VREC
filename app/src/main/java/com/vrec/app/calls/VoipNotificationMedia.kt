package com.vrec.app.calls

import java.util.Locale

/** Notification evidence may prove video; a generic ongoing call never does. */
internal object VoipNotificationMedia {
    fun type(title: String?, text: String?): String? {
        val words = listOfNotNull(title, text)
            .map { it.trim().lowercase(Locale.ROOT) }
        return if (words.any { it.contains("video call") || it.contains("video calling") ||
                it.contains("video chat") }) "VIDEO" else null
    }
}
