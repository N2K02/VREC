package com.vrec.app

import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small in-memory log, mirrored to logcat (tag "VREC"). The Setup and Settings screens can
 * show it so you can see which audio source was chosen and whether a recording came out silent,
 * without a cable. Nothing leaves the phone.
 */
object Log {
    private const val TAG = "VREC"
    private const val MAX = 400
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(message: String) {
        AndroidLog.i(TAG, message)
        add(message)
    }

    fun w(message: String, error: Throwable? = null) {
        AndroidLog.w(TAG, message, error)
        add("⚠ $message" + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    private fun add(message: String) {
        val line = "${time.format(Date())}  $message"
        _lines.value = (_lines.value + line).takeLast(MAX)
    }

    fun snapshot(): String = _lines.value.joinToString("\n")

    fun clear() {
        _lines.value = emptyList()
    }
}
