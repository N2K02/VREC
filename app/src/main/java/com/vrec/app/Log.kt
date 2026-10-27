package com.vrec.app

import android.content.Context
import android.os.SystemClock
import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Today's diagnostics only. Stored locally and automatically discarded on the next day. */
object Log {
    private const val TAG = "VREC"
    private const val MAX = 400
    private const val PREFS = "vrec_daily_activity"
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines
    private var context: Context? = null
    private var day = ""
    private var lastInfo: String? = null
    private var lastInfoAt = 0L
    private const val DUPLICATE_INFO_WINDOW_MS = 30_000L
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun time() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    @Synchronized
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        val prefs = context!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        day = today()
        _lines.value = if (prefs.getString("day", "") == day)
            prefs.getString("lines", "")!!.lines().filter { it.isNotBlank() }.takeLast(MAX)
        else emptyList()
        persist()
    }

    private fun persist() {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("day", day)?.putString("lines", _lines.value.joinToString("\n"))?.apply()
    }

    private fun rollDay() {
        val current = today()
        if (day != current) {
            day = current
            _lines.value = emptyList()
            persist()
        }
    }

    @Synchronized
    fun i(message: String) {
        // Routine health checks can repeat indefinitely. Suppress identical
        // informational messages before Android logcat and disk persistence.
        val now = SystemClock.elapsedRealtime()
        if (message == lastInfo && now - lastInfoAt in 0 until DUPLICATE_INFO_WINDOW_MS) return
        lastInfo = message
        lastInfoAt = now
        AndroidLog.i(TAG, message)
        add(message)
    }

    fun w(message: String, error: Throwable? = null) {
        AndroidLog.w(TAG, message, error)
        add("⚠ $message" + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    @Synchronized
    private fun add(message: String) {
        rollDay()
        _lines.value = (_lines.value + "${time()}  $message").takeLast(MAX)
        persist()
    }

    @Synchronized
    fun snapshot(): String {
        rollDay()
        return _lines.value.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        rollDay()
        _lines.value = emptyList()
        lastInfo = null
        lastInfoAt = 0L
        persist()
    }
}
