package com.vrec.app.experimental.daemon

import android.content.Context
import com.vrec.app.Prefs
import android.media.MediaMuxer
import android.util.Log
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Shell UID audio capture service adapted from JemRec under Apache 2.0.
 * Built-in ADB is used for ONE-TIME bootstrap; completed call audio travels
 * over an authenticated loopback channel even with Wi-Fi disconnected.
 */
object ExperimentalCaptureDaemon {
    private const val TAG = "VRecCapture"
    private const val ASSET = "vrec-capture.jar"
    private const val DEVICE_PATH = "/data/local/tmp/vrec-capture.jar"
    private const val LOG_PATH = "/data/local/tmp/vrec-capture.out"
    private const val PORT = 28473
    private const val TOKEN_KEY = "shell_daemon_token"
    private const val DAEMON_CLASS = "com.vrec.app.shell.Main"
    private const val HELLO = 'A'
    private val random = SecureRandom()
    val message = MutableStateFlow("Recorder not started.")
    val ready = MutableStateFlow(false)
    private val importMutex = Mutex()
    private val modeMutex = Mutex()
    // Lifecycle entry point for the app's ADB-bootstrapped privileged Java service.
    // The service runs as Android shell, not root, and survives ADB transport loss
    // while its process remains alive. See AdbPrivilegedApiHelper.
    val adbPrivilegedApiHelper: AdbPrivilegedApiHelper = AdbPrivilegedApiHelper


    private fun token(context: Context): String {
        val prefs = context.getSharedPreferences("vrec_experimental_recorder", Context.MODE_PRIVATE)
        prefs.getString(TOKEN_KEY, null)?.let { return it }
        val raw = ByteArray(32).also(random::nextBytes)
        return raw.joinToString("") { "%02x".format(it) }.also {
            prefs.edit().putString(TOKEN_KEY, it).apply()
        }
    }

    /** Only this app install's capture daemon knows the generated token. */
    fun isAuthorizedWake(context: Context, candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(
            token(context).toByteArray(Charsets.UTF_8),
            candidate.toByteArray(Charsets.UTF_8),
        )

    private fun mac(key: ByteArray, label: String, nonceA: ByteArray, nonceB: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(label.toByteArray(Charsets.UTF_8))
            update(nonceA)
            doFinal(nonceB)
        }

    private fun open(context: Context, command: Char, payload: ByteArray = byteArrayOf()): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", PORT), 2500)
            socket.soTimeout = 5000
            val output = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())
            val nonceA = ByteArray(16).also(random::nextBytes)
            output.write(HELLO.code)
            output.write(nonceA)
            output.flush()
            val nonceB = ByteArray(16).also(input::readFully)
            val signature = ByteArray(32).also(input::readFully)
            val key = token(context).toByteArray(Charsets.UTF_8)
            check(MessageDigest.isEqual(signature, mac(key, "vrec-daemon", nonceA, nonceB))) {
                "A different process answered the capture socket."
            }
            output.write(mac(key, "vrec-client", nonceA, nonceB))
            output.write(command.code)
            output.write(payload)
            output.flush()
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    /** Only counts as running after the server proves knowledge of our token. */
    suspend fun probe(context: Context): Boolean = withContext(Dispatchers.IO) {
        val result = runCatching {
            open(context, 'P').use {
                it.getInputStream().bufferedReader().readLine()?.startsWith("PONG 2") == true
            }
        }.getOrDefault(false)
        ready.value = result
        result
    }

    private fun stage(context: Context): String {
        val dir = context.getExternalFilesDir(null)
            ?: error("App external storage unavailable for one-time shell bootstrap.")
        return File(dir, ASSET).apply {
            context.assets.open(ASSET).use { source ->
                outputStream().use { target -> source.copyTo(target) }
            }
        }.absolutePath
    }

    private suspend fun runShell(method: ExperimentalRecording.Method, command: String): String =
        when (method) {
            ExperimentalRecording.Method.BUILT_IN_ADB -> {
                AdbTransport.exec(command, timeoutMs = 30_000).getOrThrow()
            }
            else -> error("Standard mode does not need the shell daemon")
        }

    suspend fun ensureStarted(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val method = ExperimentalRecording.method(context)
        runCatching {
            check(method != ExperimentalRecording.Method.STANDARD) {
                "Select Built-in ADB first."
            }
            if (probe(context)) {
                check(setMode(context, Prefs.recordingEnabled(context))) {
                    "Could not confirm the helper's recording mode."
                }
                message.value = if (Prefs.recordingEnabled(context))
                    "Recorder running. It can capture without a current ADB connection."
                else "Recorder running; Home recording switch is off."
                return@runCatching
            }
            val staged = stage(context)
            // External files are used only to move the executable into shell-private
            // storage. We never download shell code or run scripts from a URL.
            require(!staged.contains("'")) { "Unexpected shell staging path" }
            val copy = runShell(method,
                "cp '" + staged + "' " + DEVICE_PATH +
                    " && chmod 600 " + DEVICE_PATH + " && echo STAGED")
            check("STAGED" in copy) { "Could not stage capture jar: " + copy }
            val launch = "VREC_TOKEN=" + token(context) +
                " VREC_MODE=" + (if (Prefs.recordingEnabled(context)) "1" else "0") +
                " VREC_TRACK=0 VREC_VOIP='' VREC_ANSWER='' " +
                "CLASSPATH=" + DEVICE_PATH +
                " setsid nohup app_process / " + DAEMON_CLASS + " " + PORT +
                " < /dev/null > " + LOG_PATH + " 2>&1 &"
            runShell(method, launch)
            var started = false
            repeat(20) {
                if (!started) {
                    delay(350)
                    if (probe(context)) started = true
                }
            }
            check(started) { "Shell recorder did not start. Check capture log." }
            check(setMode(context, Prefs.recordingEnabled(context))) {
                "Shell recorder started, but its recording mode was not confirmed."
            }
            message.value = if (Prefs.recordingEnabled(context))
                "Shell recorder started. Calls can continue while Wi-Fi is off."
            else "Shell recorder started in standby; enable Recording on Home to capture."
        }.onFailure {
            ready.value = false
            message.value = "Recorder startup failed: " + (it.message ?: "unknown error")
            Log.w(TAG, message.value, it)
        }
    }

    suspend fun setMode(context: Context, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        modeMutex.withLock {
            // Serialize mode updates and reread Home preference at the point of use.
            // A late enable operation must never re-arm capture after the user switched off.
            val allowed = enabled && Prefs.recordingEnabled(context) &&
                ExperimentalRecording.method(context) != ExperimentalRecording.Method.STANDARD
            runCatching {
                open(context, 'E', byteArrayOf(if (allowed) 1 else 0)).use {
                    val confirmedMode = it.getInputStream().bufferedReader().readLine()?.trim()
                    confirmedMode == if (allowed) "1" else "0"
                }
            }.getOrDefault(false)
        }
    }

    suspend fun stopRecording(context: Context) {
        setMode(context, false)
        message.value = "Enhanced capture paused. Standard recording remains available."
    }

    /** Daemon protocol returns complete, finished call files one filename per line. */
    suspend fun pending(context: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            open(context, 'L').use {
                it.getInputStream().bufferedReader().readLines()
                    .filter { n -> n.matches(Regex("vrec_rec_[A-Za-z0-9_.-]+[.]dat")) }
            }
        }.getOrDefault(emptyList())
    }

    private fun nameBytes(name: String): ByteArray {
        require(name.matches(Regex("vrec_rec_[A-Za-z0-9_.-]+[.]dat")))
        val b = name.toByteArray(Charsets.UTF_8)
        require(b.size <= 512)
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    /** Imports only fully muxed files and only deletes the shell copy on success. */
    suspend fun collect(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        importMutex.withLock {
        runCatching {
            var collected = 0
            for (name in pending(context)) {
                val started = Regex("^vrec_rec_([0-9]{12,15})")
                    .find(name)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
                val incoming = name.contains("_in.")
                val direction = if (incoming) CallDirection.INCOMING else CallDirection.OUTGOING
                val dest = File(RecordingStore.dir(context), name.removeSuffix(".dat") + ".ogg")
                if (dest.exists() && dest.length() > 100L) {
                    delete(context, name)
                    continue
                }
                val temp = File(dest.parentFile, dest.name + ".tmp")
                var packets = 0L
                try {
                    open(context, 'F', nameBytes(name)).use { sock ->
                        sock.soTimeout = 30_000
                        AudioStream(sock).use { stream ->
                            check(stream.codecId.equals("opus", ignoreCase = true)) {
                                "Expected Opus data, got " + stream.codecId
                            }
                            val config = stream.readPacket()
                                ?: error("No Opus stream configuration")
                            check(config.isConfig) { "Missing Opus config header" }
                            temp.outputStream().use { output ->
                                // MediaMuxer can work from a FileDescriptor with full rights.
                                OpusOggWriter(output.fd, config.data).use { muxer ->
                                    while (true) {
                                        val packet = stream.readPacket() ?: break
                                        if (!packet.isConfig && packet.data.isNotEmpty()) {
                                            muxer.write(packet.data, packet.pts)
                                        }
                                    }
                                    packets = muxer.packets
                                }
                            }
                        }
                    }
                    require(packets > 0 && temp.length() > 100L) {
                        "Recorder returned no useful Opus packets"
                    }
                    check(temp.renameTo(dest)) { "Cannot finalize recording" }
                    val durationMs = packets * 20L
                    RecordingStore.writeMeta(
                        dest, direction, null, "Shell VOICE_CALL",
                        silent = false, durationMs = durationMs,
                    )
                    val sidecar = File(dest.parentFile, dest.name + ".json")
                    runCatching {
                        val json = JSONObject(sidecar.readText())
                        json.put("startedAt", started)
                        json.put("endedAt", started + durationMs)
                        json.put("source", "Enhanced shell capture")
                        sidecar.writeText(json.toString())
                    }
                    delete(context, name)
                    collected++
                } catch (t: Throwable) {
                    Log.w(TAG, "Import failed for " + name, t)
                    temp.delete()
                    throw t
                }
            }
            if (collected > 0) message.value = "Imported " + collected + " enhanced recordings."
            collected
        }.onFailure {
            message.value = "Import failed: " + it.message
        }
        }
    }

    private fun delete(context: Context, name: String) {
        runCatching {
            open(context, 'D', nameBytes(name)).use { it.getInputStream().read() }
        }
    }

    suspend fun readLog(method: ExperimentalRecording.Method): String = withContext(Dispatchers.IO) {
        runCatching { runShell(method, "tail -n 80 " + LOG_PATH) }
            .getOrElse { "Log unavailable: " + it.message }
    }
}
