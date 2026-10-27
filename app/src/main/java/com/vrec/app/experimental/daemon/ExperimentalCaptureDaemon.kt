package com.vrec.app.experimental.daemon

import android.content.Context
import com.vrec.app.Prefs
import android.media.MediaMuxer
import android.util.Log
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
import com.vrec.app.recording.CallDirection
import com.vrec.app.recording.RecordingStore
import com.vrec.app.recording.RecordingType
import com.vrec.app.recording.RecordingService
import com.vrec.app.recording.AdbFallbackNotice
import com.vrec.app.calls.CallDetectionAccess
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
    /** Whether shell AudioPolicy accepted the selected messenger apps. */
    val voipArmed = MutableStateFlow(false)
    val voipMessage = MutableStateFlow("Enhanced VoIP capture is off.")
    val simSourceMessage = MutableStateFlow("VOICE_CALL")
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

    private suspend fun runShell(command: String): String =
        AdbTransport.exec(command, timeoutMs = 30_000).getOrThrow()

    /**
     * With authorized advanced Telecom detection, outgoing OFFHOOK is only
     * dialing. The shell must wait for InCallService STATE_ACTIVE.
     * Without the observer, the existing OFFHOOK fallback remains in place.
     */
    private fun requireOutgoingAnswer(context: Context): Boolean =
        Prefs.experimentalCallDetection(context) &&
            CallDetectionAccess.isGranted(context) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    private fun shouldArmForNextCall(context: Context): Boolean =
        Prefs.recordingEnabled(context) &&
            !AdbFallbackNotice.active.value &&
            !AdbFallbackNotice.handoffInProgress.value &&
            RecordingService.state.value is RecordingService.State.Idle

    suspend fun ensureStarted(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (probe(context)) {
                if (setMode(context, Prefs.recordingEnabled(context))) {
                    message.value = if (Prefs.recordingEnabled(context))
                        "Recorder running. It can capture without a current ADB connection."
                    else "Recorder running; Home recording switch is off."
                    return@runCatching
                }
                // The previous APK may have left an old shell daemon running.
                // A new app must not pretend an old binary supports answered-call
                // gating. Only a verified ADB shell may replace it, and never
                // while it is recording or handling a call (Q responds BUSY).
                val link = ExperimentalRecording.status.value
                check(link.connected && link.shellUid == 2000) {
                    "Reauthorize ADB to update the older helper."
                }
                val exit = open(context, 'Q').use {
                    it.getInputStream().bufferedReader().readLine()?.trim()
                }
                check(exit == "BYE") {
                    "The existing helper is busy or cannot be upgraded during a call."
                }
                var exited = false
                repeat(15) {
                    if (!exited) {
                        delay(250)
                        exited = !probe(context)
                    }
                }
                check(exited) { "Old helper did not release its authenticated socket." }
            }
            val staged = stage(context)
            // External files are used only to move the executable into shell-private
            // storage. We never download shell code or run scripts from a URL.
            require(!staged.contains("'")) { "Unexpected shell staging path" }
            val copy = runShell(
                "cp '" + staged + "' " + DEVICE_PATH +
                    " && chmod 600 " + DEVICE_PATH + " && echo STAGED")
            check("STAGED" in copy) { "Could not stage capture jar: " + copy }
            val launch = "VREC_TOKEN=" + token(context) +
                " VREC_MODE=" + (if (shouldArmForNextCall(context)) "1" else "0") +
                " VREC_WAIT_OUTGOING=" + (if (requireOutgoingAnswer(context)) "1" else "0") +
                " VREC_TRACK=0 VREC_VOIP='" +
                    EnhancedCapturePreferences.voipPackages(context).joinToString(",") +
                    "' VREC_ANSWER='' " +
                "CLASSPATH=" + DEVICE_PATH +
                " setsid nohup app_process / " + DAEMON_CLASS + " " + PORT +
                " < /dev/null > " + LOG_PATH + " 2>&1 &"
            runShell( launch)
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

    suspend fun setMode(context: Context, enabled: Boolean,
                        forVerifiedHandoff: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        modeMutex.withLock {
            // Serialize mode updates and reread Home preference at the point of use.
            // A late enable operation must never re-arm capture after the user switched off.
            val allowed = enabled && Prefs.recordingEnabled(context) &&
                true &&
                if (forVerifiedHandoff)
                    AdbFallbackNotice.handoffInProgress.value &&
                        RecordingService.state.value is RecordingService.State.Idle
                else shouldArmForNextCall(context)
            val verified = runCatching {
                val waitForAnswer = allowed && requireOutgoingAnswer(context)
                open(context, 'E', byteArrayOf(
                        if (allowed) 1 else 0,
                        if (waitForAnswer) 1 else 0,
                    )).use {
                    val confirmed = it.getInputStream().bufferedReader().readLine()?.trim()
                    // Never consider an old one-byte daemon compatible with
                    // the authorized answered-call timing requirement.
                    confirmed == "${if (allowed) 1 else 0} WAIT ${if (waitForAnswer) 1 else 0}" ||
                        (!waitForAnswer && confirmed == (if (allowed) "1" else "0"))
                }
            }.getOrDefault(false)
            val configuredSource = if (verified) {
                runCatching {
                    val selected = EnhancedCapturePreferences.simSource(context)
                    open(context, 'Y', byteArrayOf(selected.wireValue.toByte())).use {
                        it.getInputStream().bufferedReader().readLine()?.trim().orEmpty()
                    }
                }.getOrDefault("")
            } else ""
            simSourceMessage.value = when {
                configuredSource.startsWith("SOURCE ") -> configuredSource
                else -> "SIM source unavailable: reconnect ADB to upgrade the helper."
            }
            // A source change requested during a live call applies on the next idle sync.
            val sourceOk = configuredSource.startsWith("SOURCE ") &&
                configuredSource != "SOURCE INVALID"
            val requestedApps = if (allowed) EnhancedCapturePreferences.voipPackages(context)
                else emptySet()
            val voipResult = if (verified) runCatching {
                val payload = requestedApps.joinToString(",").toByteArray(Charsets.UTF_8)
                require(payload.size <= 512)
                val packet = byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
                open(context, 'V', packet).use {
                    it.getInputStream().bufferedReader().readLine()?.trim().orEmpty()
                }
            }.getOrDefault("") else ""
            voipArmed.value = requestedApps.isNotEmpty() &&
                voipResult == "VOIP ${requestedApps.size} armed"
            voipMessage.value = when {
                requestedApps.isEmpty() -> "Enhanced VoIP capture off (or requires Android 14+)."
                voipArmed.value -> "Enhanced VoIP capture armed for ${requestedApps.size} apps."
                else -> "Enhanced VoIP audio policy not available on this phone."
            }
            if (!verified || !sourceOk) {
                ready.value = false
                message.value = "Enhanced helper source configuration not verified. Reconnect authorized ADB to update it."
            }
            verified && sourceOk
        }
    }

    /** Authenticated commands affect only the active shell recording. */
    suspend fun captureControl(context: Context, action: Char): String = withContext(Dispatchers.IO) {
        require(action in "PRSI")
        runCatching {
            open(context, 'Y', byteArrayOf(action.code.toByte())).use {
                it.getInputStream().bufferedReader().readLine()?.trim() ?: "UNAVAILABLE"
            }
        }.getOrElse { failure ->
            message.value = "Enhanced recording control failed: " + (failure.message ?: "helper unavailable")
            "UNAVAILABLE"
        }
    }

    enum class QuitResult { STOPPED, BUSY, NOT_RUNNING, FAILED }

    /**
     * Ask the shell helper to exit ('Q'). The daemon answers BUSY during a call
     * or an import, so Stop can never cut a recording short. Starting it again
     * needs a verified ADB session.
     */
    suspend fun quit(context: Context): QuitResult = withContext(Dispatchers.IO) {
        if (!probe(context)) return@withContext QuitResult.NOT_RUNNING
        val reply = runCatching {
            open(context, 'Q').use { it.getInputStream().bufferedReader().readLine()?.trim() }
        }.getOrNull()
        when (reply) {
            "BUSY" -> QuitResult.BUSY
            "BYE" -> {
                var gone = false
                repeat(10) { if (!gone) { delay(200); gone = !probe(context) } }
                if (gone) {
                    message.value = "Recording helper stopped."
                    QuitResult.STOPPED
                } else QuitResult.FAILED
            }
            else -> QuitResult.FAILED
        }
    }

    suspend fun stopRecording(context: Context) {
        voipArmed.value = false
        setMode(context, false)
        message.value = "Enhanced capture paused. Standard recording remains available."
    }

    /** Read a short, authenticated quality summary written by the shell after a call. */
    private fun recordingHealth(context: Context, name: String): String? = runCatching {
        open(context, 'H', nameBytes(name)).use { sock ->
            sock.getInputStream().bufferedReader().readLine()?.trim()
                ?.takeIf { it.isNotBlank() && it != "UNAVAILABLE" }?.take(600)
        }
    }.getOrNull()

    /** Daemon protocol returns complete, finished call files one filename per line. */
    suspend fun pending(context: Context): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            open(context, 'L').use {
                it.getInputStream().bufferedReader().readLines()
                    .filter { n -> n.matches(Regex("vrec_rec_[A-Za-z0-9_.-]+[.]dat")) }
            }
        }.getOrElse { failure ->
            com.vrec.app.Log.w("Capture diagnostic: unable to list shell recordings; helper may be detached", failure)
            throw failure
        }
    }

    private fun nameBytes(name: String): ByteArray {
        require(name.matches(Regex("vrec_rec_[A-Za-z0-9_.-]+[.]dat")))
        val b = name.toByteArray(Charsets.UTF_8)
        require(b.size <= 512)
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    /**
     * Require the daemon's own call watcher to recognize a live call before
     * stopping the microphone recording. A freshly launched shell helper may
     * answer pings without having received the phone's off-hook callback yet.
     * Fail closed: a missing/stale watcher keeps the normal recording running.
     */
    suspend fun canHandoffCurrentCellularCall(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!Prefs.recordingEnabled(context) ||
                false
            ) return@withContext false
            runCatching {
                open(context, 'W').use { socket ->
                    val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    check(line.startsWith("WATCH 1 "))
                    val attrs = line.substringAfter("WATCH 1 ").split(' ')
                        .mapNotNull { token ->
                            val i = token.indexOf('=')
                            if (i > 0) token.substring(0, i) to token.substring(i + 1) else null
                        }.toMap()
                    attrs["watching"] == "1" && attrs["looper"] == "ok" &&
                        attrs["call"] == "1" && attrs["recording"] == "0" &&
                        attrs["mode"] == "0"
                }
            }.getOrDefault(false)
        }

    /** Confirm an outgoing cellular call really connected, not merely went OFFHOOK. */
    suspend fun confirmOutgoingAnswered(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            if (!Prefs.recordingEnabled(context) ||
                false ||
                !requireOutgoingAnswer(context)) return@withContext false
            runCatching {
                open(context, 'C').use { socket ->
                    socket.getInputStream().bufferedReader().readLine()?.trim() == "ANSWER STARTED"
                }
            }.getOrDefault(false)
        }

    /** Start a new enhanced segment during an already-observed phone call. */
    suspend fun startCurrentCellularCall(context: Context): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(Prefs.recordingEnabled(context) &&
                    true) {
                    "Recording was disabled before ADB handoff."
                }
                open(context, 'S').use { socket ->
                    val filename = socket.getInputStream().bufferedReader().readLine()?.trim().orEmpty()
                    check(filename.matches(Regex("vrec_rec_[A-Za-z0-9_.-]+[.]dat"))) {
                        "Enhanced recorder did not confirm a new audio segment."
                    }
                    filename
                }
            }
        }

    /** Imports only fully muxed files and only deletes the shell copy on success. */
    suspend fun collect(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        importMutex.withLock {
        runCatching {
            var collected = 0
            var failures = 0
            val waiting = pending(context)
            if (waiting.isEmpty()) {
                com.vrec.app.Log.i("Capture diagnostic: shell import found no finished recordings; check whether the helper captured and finalized the call")
            } else {
                com.vrec.app.Log.i("Capture diagnostic: shell import found " + waiting.size + " pending recording(s)")
            }
            var firstFailure: String? = null
            for (name in waiting) {
                val identity = ShellRecordingIdentity.fromName(name)
                if (identity == null) {
                    Log.w(TAG, "Skipping unknown shell recording identity: " + name)
                    continue
                }
                val started = identity.startedAt
                val caller = com.vrec.app.calls.ShellCallMetadataJournal.find(
                    context, started, identity.appPackage)
                val direction = if (caller != null) CallDirection.VOIP else identity.direction
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
                    com.vrec.app.Log.i("Capture diagnostic: shell import packets=" + packets + " bytes=" + temp.length() + " file=" + name)
                    require(packets > 0 && temp.length() > 100L) {
                        "Recorder returned no useful Opus packets"
                    }
                    check(temp.renameTo(dest)) { "Cannot finalize recording" }
                    val durationMs = packets * 20L
                    val voip = direction == CallDirection.VOIP
                    val health = recordingHealth(context, name)
                    val label = if (voip) "Enhanced VoIP playback + microphone"
                        else health?.substringBefore(" | ")?.let { "Shell " + it }
                            ?: "Enhanced shell capture"
                    RecordingStore.writeMeta(
                        dest, direction, caller?.name ?: caller?.number ?:
                            ShellVoipLabels.forPackage(identity.appPackage), label,
                        silent = health?.contains("BOTH_CHANNELS_QUIET") == true ||
                            health?.contains("NO_AUDIO") == true,
                        durationMs = durationMs,
                        appPackage = caller?.packageName ?: identity.appPackage,
                        phoneNumber = caller?.number,
                        contactName = caller?.name,
                        numberSource = caller?.numberSource,
                        recordingType = RecordingType.ADB,
                    )
                    val sidecar = File(dest.parentFile, dest.name + ".json")
                    runCatching {
                        val json = JSONObject(sidecar.readText())
                        json.put("startedAt", started)
                        json.put("endedAt", started + durationMs)
                        json.put("source", label)
                        if (health != null) {
                            json.put("audioHealth", health)
                            json.put("audioHealthWarning", health.contains("QUIET") ||
                                health.contains("CLIPPING") || health.contains("NO_AUDIO"))
                        }
                        sidecar.writeText(json.toString())
                    }
                    com.vrec.app.Log.i("Capture diagnostic: saved shell recording bytes=" + dest.length() + " durationMs=" + durationMs + " audioHealth=" + (health ?: "unavailable"))
                    com.vrec.app.calls.ShellCallMetadataJournal.consume(
                        context, started, identity.appPackage)
                    delete(context, name)
                    RecordingStore.notifySaved()
                    collected++
                } catch (t: Throwable) {
                    // One bad or truncated shell file must not block later
                    // recordings in the list. Preserve the shell original;
                    // only temporary app-side output is discarded.
                    Log.w(TAG, "Import failed for " + name, t)
                    com.vrec.app.Log.w("Capture diagnostic: shell recording import failed file=" + name + " cause=" + t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
                    temp.delete()
                    failures++
                    if (firstFailure == null) firstFailure = t.message ?: t.javaClass.simpleName
                    continue
                }
            }
            message.value = ImportBatchPolicy.message(collected, failures)
            if (ImportBatchPolicy.shouldFailBatch(collected, failures)) {
                throw IOException("$failures recordings could not be imported: $firstFailure")
            }
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

    suspend fun readLog(): String = withContext(Dispatchers.IO) {
        runCatching { runShell( "tail -n 80 " + LOG_PATH) }
            .getOrElse { "Log unavailable: " + it.message }
    }
}
