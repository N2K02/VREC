package com.vrec.app

import android.content.Context
import android.util.Log as AndroidLog

/** Minimal sanitized Android Logcat wrapper; all in-app Activity Log storage is retired. */
object Log {
    private const val TAG = "VREC"

    private fun redact(message: String): String = message
        .replace(Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9 ()-]{6,}[0-9]"), "[redacted-number]")
        .replace(Regex("(?i)(token|password|secret|authorization|auth_key|api_key|access_token)=([^\\s,;]+)"), "$1=[redacted]")

    fun initialize(appContext: Context) {
        // Erase the retired Activity Log preference cache on first app launch.
        appContext.getSharedPreferences("vrec_daily_activity", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun i(message: String) {
        AndroidLog.i(TAG, redact(message).replace('\n', ' ').replace('\r', ' ').take(1000))
    }

    fun w(message: String, error: Throwable? = null) {
        val safe = redact(message).replace('\n', ' ').replace('\r', ' ').take(1000)
        AndroidLog.w(TAG, safe + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}
