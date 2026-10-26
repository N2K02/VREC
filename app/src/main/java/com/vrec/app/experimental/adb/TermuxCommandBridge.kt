package com.vrec.app.experimental.adb

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional Termux command runner. It executes in Termux's normal app UID.
 * NOT a privileged ADB shell or a way to USB-bootstrap adbd on this same phone.
 *
 * Only user-initiated commands are sent to Termux, and command input/output is
 * never added to the persistent VRec activity log (may contain secrets).
 */
object TermuxCommandBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr/bin"
    private const val COMMAND_TIMEOUT_MS = 120_000L
    const val RUN_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private const val RESULT_BUNDLE = "com.termux.RUN_COMMAND_RESULT"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERROR_CODE = "err"
    private const val RESULT_ERROR_MESSAGE = "errmsg"
    private const val EXECUTION_ID = "com.vrec.app.TERMUX_EXECUTION_ID"
    private val nextId = AtomicInteger(1000)
    private val mainHandler = Handler(Looper.getMainLooper())

    data class State(
        val busy: Boolean = false,
        val output: String = "Termux runs commands as a normal Android app, not shell UID 2000.",
        val error: String? = null,
        val command: String? = null,
        val executionId: Int = 0,
    )
    val status = MutableStateFlow(State())

    fun installed(context: Context): Boolean =
        try { context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Returns immediate launch failure or null when Termux accepted the intent. */
    fun run(context: Context, command: String): String? {
        val app = context.applicationContext
        val trimmed = command.trim()
        if (status.value.busy) return "A Termux command is already running."
        if (trimmed.isEmpty() || trimmed.length > 2048 || trimmed.any { it == '\u0000' })
            return "Enter a command of at most 2048 characters."
        if (!installed(app)) return "Install Termux from F-Droid first."
        if (!permitted(app))
            return "Grant VRec the Additional permission 'Run commands in Termux environment'."
        val id = nextId.incrementAndGet()
        val resultIntent = Intent(app, TermuxResultService::class.java).putExtra(EXECUTION_ID, id)
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getService(app, id, resultIntent, flags)
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", trimmed))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        status.value = State(busy = true, output = "$ $trimmed\nRunning in Termux…",
            command = trimmed, executionId = id)
        return try {
            app.startService(intent)
            mainHandler.postDelayed({
                val current = status.value
                if (current.executionId == id && current.busy) {
                    status.value = current.copy(busy = false,
                        error = "No result received. Check Termux version (0.109+), allow-external-apps and permissions.",
                        output = current.output + "\nTermux did not respond.")
                }
            }, COMMAND_TIMEOUT_MS)
            null
        } catch (t: Exception) {
            callback.cancel()
            val error = "Could not start Termux. Enable allow-external-apps and VRec's Termux permission: ${t.message}"
            status.value = status.value.copy(busy = false, error = error, output = error)
            error
        }
    }

    fun onResult(intent: Intent) {
        val id = intent.getIntExtra(EXECUTION_ID, -1)
        val current = status.value
        if (!current.busy || current.executionId != id) return
        val bundle: Bundle? = intent.getBundleExtra(RESULT_BUNDLE)
        if (bundle == null) {
            status.value = current.copy(busy = false,
                error = "Termux returned no result. Check the Termux bridge configuration.")
            return
        }
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty().takeLast(10000)
        val stderr = bundle.getString(RESULT_STDERR).orEmpty().takeLast(4000)
        val exit = bundle.getInt(RESULT_EXIT_CODE, -1)
        val err = bundle.getInt(RESULT_ERROR_CODE, -1)
        val errMessage = bundle.getString(RESULT_ERROR_MESSAGE).orEmpty()
        val failure = when {
            errMessage.isNotBlank() -> errMessage.take(800)
            exit != 0 -> "Command exited with code $exit."
            else -> null
        }
        status.value = current.copy(
            busy = false,
            error = failure,
            output = buildString {
                append("$ ").append(current.command).append('\n')
                if (stdout.isNotBlank()) append(stdout).append('\n')
                if (stderr.isNotBlank()) append(stderr).append('\n')
                if (failure != null) append(failure).append('\n')
                if (stdout.isBlank() && stderr.isBlank() && failure == null)
                    append("(Command completed with no output)")
            }.takeLast(16000),
        )
    }
}

/** PendingIntent callback is explicitly addressed to VRec and never exported. */
class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let(TermuxCommandBridge::onResult)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
