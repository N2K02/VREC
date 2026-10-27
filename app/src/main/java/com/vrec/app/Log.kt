package com.vrec.app

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
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
    private val lastInfoTimes = LinkedHashMap<String, Long>()
    private const val MAX_DEDUP_KEYS = 128
    private const val DUPLICATE_INFO_WINDOW_MS = 30_000L
    private fun redact(message: String): String = message
        .replace(Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9 ()-]{6,}[0-9]"), "[redacted-number]")
        .replace(Regex("(?i)(token|password|secret|authorization|auth_key|api_key|access_token)=([^\\s,;]+)"), "$1=[redacted]")

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

    private val writeHandler = Handler(Looper.getMainLooper())
    private val flush = Runnable { synchronized(this) { persistNow() } }
    private fun persist() {
        writeHandler.removeCallbacks(flush)
        writeHandler.postDelayed(flush, 1500L)
    }
    private fun persistNow() {
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
        val safe = redact(message)
        val previous = lastInfoTimes[safe]
        if (previous != null && now - previous in 0 until DUPLICATE_INFO_WINDOW_MS) return
        lastInfoTimes[safe] = now
        if (lastInfoTimes.size > MAX_DEDUP_KEYS) lastInfoTimes.remove(lastInfoTimes.keys.first())
        AndroidLog.i(TAG, safe)
        add(safe)
    }

    fun w(message: String, error: Throwable? = null) {
        val safe = redact(message)
        AndroidLog.w(TAG, safe)
        add("⚠ $safe" + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
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
        lastInfoTimes.clear()
        writeHandler.removeCallbacks(flush)
        persistNow()
    }
}
