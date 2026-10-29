package com.vrec.app.experimental.daemon

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.CancellationException
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
    private val incompatibleProtocol = MutableStateFlow(false)
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

    /** The authenticated PONG includes a build fingerprint on recent daemons. */
    private suspend fun pingBanner(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            open(context, 'P').use {
                it.getInputStream().bufferedReader().readLine()?.trim()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A PONG proves that the process is alive and authenticated, NOT that
     * recording mode and SIM source configuration have been acknowledged.
     */
    suspend fun probe(context: Context): Boolean {
        val result = pingBanner(context)?.startsWith("PONG 2") == true
        if (!result) ready.value = false
        return result
    }

    @Volatile private var cachedBundledBuild: String? = null

    private fun bundledBuild(context: Context): String =
        cachedBundledBuild ?: synchronized(this) {
            cachedBundledBuild ?: context.assets.open(ASSET)
                .use(ShellHelperBuildPolicy::fingerprint)
                .also { cachedBundledBuild = it }
        }

    /** Detect an obsolete daemon without sending newer commands it cannot answer. */
    internal suspend fun runningBuildStatus(context: Context): ShellHelperBuildPolicy.Decision {
        val banner = pingBanner(context)
        if (banner?.startsWith("PONG 2") != true) {
            return ShellHelperBuildPolicy.Decision.INCOMPATIBLE_PROTOCOL
        }
        return ShellHelperBuildPolicy.decide(banner, bundledBuild(context))
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

    private data class ShellStagedJar(val path: String, val cleanup: () -> Unit)

    /**
     * Scoped-storage fallback: on some phones UID 2000 cannot read
     * /sdcard/Android/data/<package>/files even though the app can write it.
     * MediaStore Downloads allows the app to publish an ephemeral asset without
     * any broad storage permission. Remove it immediately after shell copying.
     * No keys, pairing codes or call data are included in this static JAR.
     */
    private fun stageViaDownloads(context: Context): ShellStagedJar {
        check(Build.VERSION.SDK_INT >= 29) { "MediaStore Downloads needs Android 10+" }
        val name = "vrec-shell-" + java.util.UUID.randomUUID().toString() + ".jar"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/java-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android did not allow temporary Downloads staging")
        try {
            val output = resolver.openOutputStream(uri)
                ?: error("Android could not open the temporary JAR destination")
            output.use { target ->
                context.assets.open(ASSET).use { source -> source.copyTo(target) }
            }
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        val sharedPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name,
        ).absolutePath
        return ShellStagedJar(sharedPath) {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    private suspend fun shellCanRead(path: String): Boolean {
        if (path.contains("'")) return false
        return runShell("if test -r '$path'; then echo READABLE; else echo UNREADABLE; fi")
            .lineSequence().any { it.trim() == "READABLE" }
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
        Prefs.recordingEnabled(context)

    private fun requestedMode(context: Context, enabled: Boolean): Int =
        CaptureSetupProtocol.selectedMode(
            enabled && shouldArmForNextCall(context), Prefs.autoRecord(context))

    /** A daemon can answer PONG before its configuration worker has settled.
     * Retry short transient handshakes; never recreate a working helper just
     * because a configuration packet was delayed.
     */
    private suspend fun confirmSetup(context: Context): Boolean =
        CaptureSetupProtocol.verifyWithRetry(
            configure = { setMode(context, Prefs.recordingEnabled(context)) },
            incompatible = { incompatibleProtocol.value },
            waitBetween = { attempt -> delay(400L * attempt) },
        )

    suspend fun ensureStarted(context: Context, manualRepair: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val runningBanner = pingBanner(context)
            if (runningBanner?.startsWith("PONG 2") == true) {
                val buildDecision = ShellHelperBuildPolicy.decide(runningBanner, bundledBuild(context))
                if (buildDecision != ShellHelperBuildPolicy.Decision.STALE) {
                    if (confirmSetup(context)) {
                        message.value = if (Prefs.recordingEnabled(context))
                            "Recorder running. It can capture without a current ADB connection."
                        else "Recorder running; Home recording switch is off."
                        return@runCatching
                    }
                    // Background health checks must not restart the daemon just
                    // because an acknowledgement timed out. An explicit Start/
                    // Repair action may request one guarded Q replacement.
                    check(ShellHelperRepairPolicy.shouldRequestReplacement(
                        buildDecision, incompatibleProtocol.value, manualRepair,
                    )) {
                        "Existing shell helper is answering, but configuration could not be confirmed: " + message.value
                    }
                    if (manualRepair && !incompatibleProtocol.value) {
                        com.vrec.app.Log.i("Shell capture: user requested safe restart of unresponsive helper")
                    }
                } else {
                    com.vrec.app.Log.i("Shell capture: running helper build differs from bundled APK; requesting safe upgrade")
                }
                // A stale APK may leave a running daemon that still answers PONG,
                // but cannot process newer SIM-source commands. The authenticated
                // Q command refuses to exit during a call, recording, or import.
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
            val appStage = runCatching { stage(context) }.getOrNull()
            val directReadable = appStage != null && shellCanRead(appStage)
            val stagingRoute = ShellJarStagingPolicy.choose(directReadable, Build.VERSION.SDK_INT)
            val sharedStage = if (stagingRoute == ShellJarStagingPolicy.Route.MEDIASTORE_DOWNLOADS)
                stageViaDownloads(context) else null
            val staged = when (stagingRoute) {
                ShellJarStagingPolicy.Route.APP_EXTERNAL -> appStage
                    ?: error("App-specific shell staging path unavailable")
                ShellJarStagingPolicy.Route.MEDIASTORE_DOWNLOADS ->
                    sharedStage?.path ?: error("Temporary Downloads staging unavailable")
                ShellJarStagingPolicy.Route.UNSUPPORTED ->
                    error("Android shell cannot read VRec's staging file. Check external storage availability.")
            }
            try {
                // Only move the bundled static asset to shell-private storage.
                // The optional shared Downloads copy is deleted in finally.
                check(shellCanRead(staged)) {
                    "Android shell cannot read the temporary capture JAR (scoped storage)."
                }
                val copy = runShell(
                    "cp '" + staged + "' " + DEVICE_PATH +
                        " && chmod 600 " + DEVICE_PATH + " && echo STAGED")
                check("STAGED" in copy) { "Could not stage capture jar: " + copy }
            } finally {
                sharedStage?.cleanup?.invoke()
            }
            val launch = "VREC_TOKEN=" + token(context) +
                " VREC_MODE=" + requestedMode(context, Prefs.recordingEnabled(context)) +
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
            if (!started) {
                // The shell command can exit successfully even when the detached
                // app_process immediately crashes. Classify the shell-side log
                // without exposing call metadata or full logs in the UI.
                val tail = runCatching {
                    runShell("tail -n 16 " + LOG_PATH)
                }.getOrDefault("")
                val cause = when {
                    tail.contains("ClassNotFoundException", ignoreCase = true) ->
                        "capture jar missing a required class"
                    tail.contains("Permission denied", ignoreCase = true) ||
                        tail.contains("EACCES", ignoreCase = true) ->
                        "Android denied shell file or audio access"
                    tail.contains("Address already in use", ignoreCase = true) ->
                        "capture control port is already occupied"
                    tail.contains("Exception", ignoreCase = true) ||
                        tail.contains("FATAL", ignoreCase = true) ->
                        "shell runtime error (see ADB diagnostics)"
                    else -> "no authenticated response (check ADB diagnostics)"
                }
                error("Shell recorder did not start: $cause")
            }
            check(confirmSetup(context)) {
                "Shell recorder started, but configuration could not be confirmed: " + message.value
            }
            message.value = if (Prefs.recordingEnabled(context))
                "Shell recorder started. Calls can continue while Wi-Fi is off."
            else "Shell recorder started in standby; enable Recording on Home to capture."
        }.onFailure {
            // runCatching also catches coroutine cancellation. A Quit while
            // launching must propagate rather than being misreported as a
            // broken recorder and leaving an inconsistent starting state.
            if (it is CancellationException) throw it
            ready.value = false
            message.value = "Recorder startup failed: " + (it.message ?: "unknown error")
            Log.w(TAG, message.value, it)
        }
    }

    suspend fun setMode(context: Context, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        modeMutex.withLock {
            // Serialize mode updates and reread Home preference at the point of use.
            // A late enable operation must never re-arm capture after the user switched off.
            val mode = requestedMode(context, enabled)
            val allowed = mode != 0
            val waitForAnswer = mode == 1 && requireOutgoingAnswer(context)
            val modeReply = runCatching {
                open(context, 'E', byteArrayOf(
                    mode.toByte(),
                    if (waitForAnswer) 1 else 0,
                )).use {
                    it.getInputStream().bufferedReader().readLine()?.trim()
                }
            }.getOrNull()
            val selected = EnhancedCapturePreferences.simSource(context)
            var sourceTransportError: String? = null
            val sourceReply = if (CaptureSetupProtocol.modeAcceptedMode(modeReply, mode, waitForAnswer)) {
                CaptureSourceHandshake.request(
                    read = {
                        open(context, 'J', byteArrayOf(selected.wireValue.toByte())).use {
                            it.getInputStream().bufferedReader().readLine()
                        }
                    },
                    waitBetween = { delay(150L) },
                    onTransportError = { sourceTransportError = it.javaClass.simpleName },
                )
            } else null
            val verdict = CaptureSetupProtocol.evaluateMode(
                modeReply, sourceReply, mode, waitForAnswer, selected.wireValue,
            )
            val verified = verdict.ready
            incompatibleProtocol.value = verdict.incompatible
            simSourceMessage.value = when (verdict.source) {
                CaptureSetupProtocol.Source.APPLIED -> "SOURCE ${selected.wireValue}"
                CaptureSetupProtocol.Source.DEFERRED_DURING_CALL ->
                    "SIM source change deferred until the current call ends."
                else -> verdict.detail
            }
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
                voipArmed.value -> "VoIP watcher armed for ${requestedApps.size} apps; audio quality requires a saved test call."
                else -> "Enhanced VoIP audio policy not available on this phone."
            }
            ready.value = verified
            if (!verified) {
                val detail = verdict.detail +
                    (sourceTransportError?.let { " (SIM command transport: $it)" } ?: "")
                message.value = detail +
                    ". The helper remains available for a safe retry; check diagnostics."
                com.vrec.app.Log.w("Shell capture setup: " + detail)
            } else if (verdict.source == CaptureSetupProtocol.Source.DEFERRED_DURING_CALL) {
                message.value = verdict.detail
            }
            verified
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

    /** Typed, authenticated snapshot for recovery after the Android process dies.
     * Only the shell can assert a recording filename. Malformed replies fail
     * closed and never create an apparent recording.
     */
    sealed class CaptureSnapshot {
        data class Recording(val file: String) : CaptureSnapshot()
        object Paused : CaptureSnapshot()
        object Idle : CaptureSnapshot()
        object Unavailable : CaptureSnapshot()
    }

    internal fun parseCaptureSnapshot(reply: String): CaptureSnapshot = when {
        reply == "IDLE" -> CaptureSnapshot.Idle
        reply == "PAUSED" -> CaptureSnapshot.Paused
        reply.startsWith("RECORDING ") -> {
            val file = reply.removePrefix("RECORDING ")
            if (file.length <= 200 &&
                file.matches(Regex("vrec_rec_[A-Za-z0-9_.-]+[.]dat")))
                CaptureSnapshot.Recording(file)
            else CaptureSnapshot.Unavailable
        }
        else -> CaptureSnapshot.Unavailable
    }

    suspend fun captureSnapshot(context: Context): CaptureSnapshot =
        parseCaptureSnapshot(captureControl(context, 'I'))

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
        message.value = "Enhanced capture paused. Shell recording remains disabled until rearmed."
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
            if (!Prefs.recordingEnabled(context)) return@withContext false
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
                !requireOutgoingAnswer(context)) return@withContext false
            runCatching {
                open(context, 'C').use { socket ->
                    socket.getInputStream().bufferedReader().readLine()?.trim() == "ANSWER STARTED"
                }
            }.getOrDefault(false)
        }

    /** Start a new shell segment during an independently confirmed active call.
     * The authenticated daemon chooses the actual cellular/VoIP source; it refuses
     * to start if its watcher has no active call.
     */
    suspend fun startCurrentCall(context: Context): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(Prefs.recordingEnabled(context)) {
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
                // No completed recordings is normal during periodic collection.
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
                val journal = com.vrec.app.calls.ShellCallMetadataJournal
                val caller = journal.find(context, started, identity.appPackage)
                    ?: if (identity.appPackage == null) {
                        // Some WhatsApp video calls are recorded under a generic
                        // _in/_out shell filename. Restore source/caller only when
                        // one new-format VoIP session matches and no SIM competes.
                        journal.findForCellularTaggedVoip(context, started, identity.direction)
                            ?: journal.findForCellularTaggedSim(context, started, identity.direction)
                    } else null
                val direction = if (caller?.direction == CallDirection.VOIP)
                    CallDirection.VOIP else identity.direction
                val dest = File(RecordingStore.dir(context), name.removeSuffix(".dat") + ".ogg")
                if (dest.exists() && dest.length() > 100L &&
                    RecordingStore.hasValidMetadata(dest)) {
                    // A completed import already has audio AND metadata.
                    // Preserve the shell original if a prior import crashed
                    // after publishing audio but before metadata was written.
                    delete(context, name)
                    continue
                }
                val temp = File(dest.parentFile, dest.name + ".tmp")
                // Never leave a stale partial import as a valid recording.
                if (dest.exists() && !RecordingStore.hasValidMetadata(dest)) {
                    check(dest.delete()) { "Cannot remove incomplete previous import" }
                }
                temp.delete()
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
                    if (ImportBatchPolicy.shouldDiscardSilent(Prefs.discardSilent(context), health)) {
                        check(dest.delete()) { "Cannot discard confirmed silent audio" }
                        if (caller != null) journal.consumeMatch(context, caller)
                        else journal.consume(context, started, identity.appPackage)
                        delete(context, name)
                        com.vrec.app.Log.i("Capture diagnostic: discarded confirmed silent shell recording")
                        continue
                    }
                    val label = if (voip) "Enhanced VoIP playback + microphone"
                        else health?.substringBefore(" | ")?.let { "Shell " + it }
                            ?: "Enhanced shell capture"
                    // Resolve an exact phone number through Android Contacts
                    // when available, without ever guessing a caller by app
                    // name or nearby notification. Private callers have no
                    // number to look up.
                    val contactName = caller?.name?.takeIf { it.isNotBlank() }
                        ?: caller?.number?.let { number ->
                            com.vrec.app.calls.CallInfo.describe(context, number)
                                ?.takeIf { it != number }
                        }
                    check(RecordingStore.writeMeta(
                        dest, direction, contactName ?: caller?.number ?:
                            ShellVoipLabels.forPackage(caller?.packageName ?: identity.appPackage), label,
                        silent = health?.contains("BOTH_CHANNELS_QUIET") == true ||
                            health?.contains("NO_AUDIO") == true,
                        durationMs = durationMs,
                        appPackage = caller?.packageName ?: identity.appPackage,
                        phoneNumber = caller?.number,
                        contactName = contactName,
                        numberSource = caller?.numberSource,
                        callFlowDirection = caller?.flowDirection
                            ?: identity.direction.takeIf {
                                it == CallDirection.INCOMING || it == CallDirection.OUTGOING
                            }?.name,
                        recordingType = RecordingType.ADB,
                        callMediaType = caller?.mediaType,
                        startedAtOverride = started,
                        endedAtOverride = started + durationMs,
                        audioHealth = health,
                    )) { "Cannot save recording metadata" }
                    com.vrec.app.Log.i("Capture diagnostic: saved shell recording bytes=" + dest.length() + " durationMs=" + durationMs + " audioHealth=" + (health ?: "unavailable"))
                    // Consume by the verified source package, including when
                    // a generic shell filename was reclassified as VoIP.
                    if (caller != null) journal.consumeMatch(context, caller)
                    else journal.consume(context, started, identity.appPackage)
                    delete(context, name)
                    RecordingStore.notifySaved()
                    collected++
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // One bad or truncated shell file must not block later
                    // recordings in the list. Preserve the shell original;
                    // only temporary app-side output is discarded.
                    Log.w(TAG, "Import failed for " + name, t)
                    com.vrec.app.Log.w("Capture diagnostic: shell recording import failed file=" + name + " cause=" + t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
                    temp.delete()
                    failures++
                    com.vrec.app.recording.RecordingFailureNotice.warn(
                        context, "A recording could not be saved. Check free storage and Diagnostics; " +
                            "the original shell capture was kept for retry.", keepUntilDismissed = true)
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
