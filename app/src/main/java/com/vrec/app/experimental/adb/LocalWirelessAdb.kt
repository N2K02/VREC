package com.vrec.app.experimental.adb

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.vrec.app.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Discover the on-device TLS pairing/connection listeners via mDNS.
 * Addresses must belong to THIS device. All actual ADB traffic goes to
 * localhost, not to another network device. No code is ever persisted.
 */
object LocalWirelessAdb {
    /**
     * Where the wireless setup is. Transitions happen only inside [transition],
     * so discovery, pairing and Retry can't run the sequence twice at once.
     * RECOVERY_REQUIRED stops automatic attempts until the user retries.
     */
    enum class Phase { IDLE, SEARCHING, PAIRING, CONNECTING, SWITCHING, VERIFYING_TCP, READY, RECOVERY_REQUIRED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val supported: Boolean = Build.VERSION.SDK_INT >= 30,
        val pairingPort: Int? = null,
        val connectPort: Int? = null,
        val scanning: Boolean = false,
        val pairing: Boolean = false,
        val message: String = "Checking Android Wireless debugging…",
        val paired: Boolean = false,
    )
    val status = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val NOTIFICATION_CHANNEL = "vrec_adb_pairing"
    private const val NOTIFICATION_ID = 7601
    private var pairingDiscovery: NsdManager.DiscoveryListener? = null
    private var connectDiscovery: NsdManager.DiscoveryListener? = null
    private var nsd: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var attached = false
    @Volatile private var pairedInThisSession = false
    @Volatile private var lastAutoPort = -1
    @Volatile private var emptySearches = 0
    @Volatile private var nextAutoSearchAt = 0L
    @Volatile private var searchSession = 0
    /** Single flight for connect → UID 2000 → TCP switch → helper. */
    private val transition = Mutex()
    private const val MAX_EMPTY_SEARCHES = 5
    private const val AUTO_SEARCH_MS = 60_000L
    private const val USER_SEARCH_MS = 5 * 60_000L
    private const val AUTO_RETRY_BASE_MS = 30_000L
    private const val AUTO_RETRY_MAX_MS = 10 * 60_000L
    @Volatile private var lastAutoAttemptMs = 0L
    @Volatile private var lastTcpPromotionAtMs = 0L

    /**
     * Automatic searches are bounded: each stops after 60 s without finding
     * a service, the next waits 30 s doubling to 10 min, and after
     * [MAX_EMPTY_SEARCHES] empty searches automatic discovery pauses until
     * the user acts. A user-initiated search resets this and runs 5 min so a
     * first-time pairing in Settings isn't cut off.
     */
    fun start(context: Context, userInitiated: Boolean = false) {
        val app = context.applicationContext
        if (AdbLifecycle.quitting) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (userInitiated) {
            emptySearches = 0
            nextAutoSearchAt = 0L
        } else if (emptySearches >= MAX_EMPTY_SEARCHES || now < nextAutoSearchAt) {
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            status.value = State(supported = false,
                message = "Wireless debugging requires Android 11+. Use USB ADB bootstrap.")
            return
        }
        synchronized(this) {
            if (attached) return
            attached = true
        }
        val service = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (service == null) {
            status.value = State(message = "Wireless debugging discovery unavailable; use USB host.")
            attached = false
            return
        }
        nsd = service
        // OEMs may suppress mDNS multicast in background while Android's
        // code-entry dialog or Settings has focus. Keep multicast reception
        // available for the short first-time pairing workflow.
        multicastLock = runCatching {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.createMulticastLock("vrec-wireless-adb").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        status.value = State(scanning = true, phase = Phase.SEARCHING,
            paired = ExperimentalRecording.wirelessWasPaired(app),
            message = "Searching for this phone's Wireless debugging service…")
        val session = ++searchSession
        scope.launch {
            kotlinx.coroutines.delay(if (userInitiated) USER_SEARCH_MS else AUTO_SEARCH_MS)
            val st = status.value
            if (session == searchSession && attached && st.pairingPort == null &&
                st.connectPort == null && !st.pairing &&
                !ExperimentalRecording.status.value.connected) {
                emptySearches++
                nextAutoSearchAt = android.os.SystemClock.elapsedRealtime() +
                    (AUTO_RETRY_BASE_MS shl (emptySearches - 1).coerceAtMost(10))
                        .coerceAtMost(AUTO_RETRY_MAX_MS)
                stop(app)
                status.value = status.value.copy(message =
                    if (emptySearches >= MAX_EMPTY_SEARCHES)
                        "Wireless debugging not found. Search paused to save battery; tap Set up to search again."
                    else "Wireless debugging not found. VRec will look again later.")
                Log.i("Wireless ADB discovery: nothing found (empty search $emptySearches)")
            }
        }
        pairingDiscovery = discover(app, service, "_adb-tls-pairing._tcp", true, session)
        connectDiscovery = discover(app, service, "_adb-tls-connect._tcp", false, session)
    }

    private fun discover(
        app: Context, nsdManager: NsdManager, type: String, pairing: Boolean, session: Int,
    ): NsdManager.DiscoveryListener {
        // Android may deliver found/resolved/lost callbacks after stop(); a
        // callback from an older search must not change state or connect.
        fun stale() = session != searchSession || !attached
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                if (stale()) return
                status.value = status.value.copy(scanning = false,
                    message = "Wireless discovery unavailable ($code). Use USB bootstrap or open Developer options.")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                // Service may stop when the Android pairing-code dialog closes.
                if (stale()) return
                status.value = if (pairing) status.value.copy(pairingPort = null)
                    else status.value.copy(connectPort = null)
            }
            @Suppress("DEPRECATION")
            override fun onServiceFound(info: NsdServiceInfo) {
                if (stale()) return
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        if (stale()) return
                        val host = service.host ?: return
                        // Don't accept another device found on a shared Wi-Fi LAN.
                        if (!belongsToThisPhone(host) || service.port !in 1..65535) return
                        val port = service.port
                        emptySearches = 0
                        if (pairing) {
                            status.value = status.value.copy(pairingPort = port, scanning = false,
                                message = "Pairing code detected. Enter the six-digit code from Android.")
                            showPairingNotification(app)
                        } else {
                            status.value = status.value.copy(connectPort = port, scanning = false,
                                message = "This phone's Wireless debugging is available.",
                                paired = ExperimentalRecording.wirelessWasPaired(app))
                            val now = android.os.SystemClock.elapsedRealtime()
                            if ((ExperimentalRecording.wirelessRecoveryAllowed(app) ||
                                    pairedInThisSession) &&
                                // mDNS re-announces; one automatic attempt per port per 30 s.
                                (port != lastAutoPort || now - lastAutoAttemptMs > 30_000L) &&
                                !ExperimentalRecording.awaitingManualRetry &&
                                status.value.phase != Phase.RECOVERY_REQUIRED &&
                                !transition.isLocked &&
                                !ExperimentalRecording.status.value.connected &&
                                !ExperimentalRecording.status.value.busy) {
                                lastAutoPort = port
                                lastAutoAttemptMs = now
                                scope.launch {
                                    // Skip if pairing or Retry got there first.
                                    if (!transition.tryLock()) return@launch
                                    try {
                                        if (!stale()) AdbLifecycle.coordinateSetup {
                                            if (!stale()) connectAndSwitch(app, port, automatic = true)
                                        }
                                    } finally { transition.unlock() }
                                }
                            }
                        }
                    }
                })
            }
        }
        runCatching { nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                status.value = status.value.copy(scanning = false,
                    message = "Wireless discovery failed; USB ADB bootstrap is available.")
            }
        return listener
    }

    private fun belongsToThisPhone(address: InetAddress): Boolean =
        runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { Collections.list(it.inetAddresses) }
                .any { it == address }
        }.getOrDefault(false)

    fun pair(context: Context, code: String) {
        val app = context.applicationContext
        if (!code.matches(Regex("[0-9]{6}"))) {
            status.value = status.value.copy(message = "Enter the six-digit Android pairing code.")
            return
        }
        val pairingPort = status.value.pairingPort
        if (pairingPort == null || status.value.pairing) {
            status.value = status.value.copy(
                message = "Open Android's 'Pair device with pairing code' first.")
            return
        }
        status.value = status.value.copy(pairing = true, phase = Phase.PAIRING,
            message = "Pairing with Android…")
        scope.launch { transition.withLock {
            AdbLifecycle.coordinateSetup {
            val outcome = withTimeoutOrNull(30_000L) {
                AdbTransport.pairLocal(app, pairingPort, code)
            } ?: Result.failure(IllegalStateException("Pairing timed out"))
            // Never retain or log pairing code.
            if (outcome.isSuccess) pairedInThisSession = true
            status.value = if (outcome.isSuccess) status.value.copy(
                pairing = false, paired = true,
                message = "Paired. Connecting to the Android shell…")
            else status.value.copy(pairing = false, phase = Phase.RECOVERY_REQUIRED,
                message = "Pairing failed: ${outcome.exceptionOrNull()?.message}")
            if (outcome.isSuccess) {
                val port = status.value.connectPort
                if (port != null) {
                    if (!connectAndSwitch(app, port, automatic = false) &&
                        !ExperimentalRecording.status.value.connected)
                        status.value = status.value.copy(message =
                            "Paired, but could not connect. Retry from Home.")
                } else {
                    status.value = status.value.copy(message =
                        "Paired successfully. Waiting for Android Wireless debugging connection service…")
                }
            }
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
            }
        } }
    }

    /**
     * Must be called while holding [transition]. Re-checks state first, so a
     * Retry that waited behind an automatic attempt doesn't connect twice.
     */
    private suspend fun connectAndSwitch(app: Context, port: Int, automatic: Boolean): Boolean {
        val link = ExperimentalRecording.status.value
        if (link.connected && link.shellUid == 2000 &&
            (!AdbUserChoices.tcpMode(app) || ExperimentalRecording.tcpPortListening()) &&
            com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon.probe(app)) {
            status.value = status.value.copy(phase = Phase.READY)
            return true
        }
        status.value = status.value.copy(phase = Phase.CONNECTING)
        val result = ExperimentalRecording.connectWireless(app, port, automatic = automatic)
        if (result.isFailure) {
            status.value = status.value.copy(phase = Phase.RECOVERY_REQUIRED,
                message = ExperimentalRecording.failureMessage(result.exceptionOrNull().adbFailure,
                    result.exceptionOrNull()?.message))
            return false
        }
        // Default: switch verified shell UID 2000 to TCP/5555 and reconnect.
        // The user may opt out in Settings. The listener may be network-reachable.
        if (AdbUserChoices.tcpMode(app)) return switchToLocalTcp(app)
        val helperAnswers = com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon.probe(app)
        stop(app)
        status.value = status.value.copy(
            phase = if (helperAnswers) Phase.READY else Phase.RECOVERY_REQUIRED,
            message = if (helperAnswers)
                "Wireless debugging verified (UID 2000) and recording helper answering."
                else "Wireless debugging verified, but the recording helper didn't answer. Retry from Home.")
        return helperAnswers
    }

    /** Quit: stop discovery and cancel any in-flight pairing or connect. */
    fun shutdown(context: Context) {
        scope.coroutineContext.cancelChildren()
        stop(context)
        status.value = status.value.copy(phase = Phase.IDLE)
    }

    private suspend fun switchToLocalTcp(context: Context): Boolean {
        // The shell itself changes adbd transport. It may drop the TLS connection.
        // Only report readiness after reconnecting and rechecking UID 2000.
        status.value = status.value.copy(phase = Phase.SWITCHING)
        val switched = AdbTransport.switchToTcpip(5555)
        if (switched.isFailure) {
            status.value = status.value.copy(phase = Phase.RECOVERY_REQUIRED, message =
                "Wireless shell verified, but TCP/5555 switch failed: ${switched.exceptionOrNull()?.message}")
            return false
        }
        status.value = status.value.copy(phase = Phase.VERIFYING_TCP)
        // adbd restarts on tcpip: wait for the listener instead of a fixed pause,
        // then reconnect with retries for transient resets.
        var listening = false
        // Bounded window (15 s) for phones that restart adbd slowly.
        repeat(20) { if (!listening) { kotlinx.coroutines.delay(750L)
            listening = ExperimentalRecording.tcpPortListening() } }
        if (!listening) {
            status.value = status.value.copy(phase = Phase.RECOVERY_REQUIRED, message =
                "TCP/5555 did not come up after Android restarted ADB. Turn Wireless debugging off " +
                    "and on in Developer options, then Retry from Home.")
            return false
        }
        // Re-verifies UID 2000 on the new transport and requires the helper to answer.
        val restored = com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
            .reconnectTcp(context, automatic = false) == null
        if (restored) {
            stop(context)
            status.value = status.value.copy(phase = Phase.READY, message =
                "TCP/5555 verified. Wireless discovery stopped; recording helper monitored normally.")
        } else {
            // Wireless discovery has nothing more to do; background TCP recovery
            // (with backoff) or the user's Retry takes it from here.
            stop(context)
            // TCP/5555 may now be listening without a session to turn it off.
            status.value = status.value.copy(phase = Phase.RECOVERY_REQUIRED, message =
                "TCP/5555 may still be listening, but VRec couldn't verify it: " +
                    ExperimentalRecording.status.value.message +
                    " To close it, turn USB debugging off in Developer options, or Retry and use Disable TCP.")
        }
        return restored
    }

    /**
     * A previously verified TLS shell may still be attached after an upgrade
     * or setting change. Promote it without requiring the user to pair again.
     * Throttle unsuccessful attempts and never disturb a busy capture.
     */
    fun promoteVerifiedShellToTcp(context: Context) {
        val app = context.applicationContext
        val link = ExperimentalRecording.status.value
        if (AdbLifecycle.quitting || !AdbUserChoices.tcpMode(app) ||
            link.shellUid != 2000 || !link.connected || link.busy ||
            !AdbTransport.isConnected ||
            com.vrec.app.recording.AdbFallbackNotice.enhancedActive.value ||
            com.vrec.app.calls.CallMonitor.hasLiveCall() ||
            ExperimentalRecording.awaitingManualRetry ||
            ExperimentalRecording.tcpPortListening()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTcpPromotionAtMs < 5 * 60_000L) return
        lastTcpPromotionAtMs = now
        scope.launch {
            if (!transition.tryLock()) return@launch
            try {
                AdbLifecycle.coordinateSetup {
                    val current = ExperimentalRecording.status.value
                    if (!AdbLifecycle.quitting && current.connected &&
                        current.shellUid == 2000 && !current.busy &&
                        AdbTransport.isConnected &&
                        !com.vrec.app.recording.AdbFallbackNotice.enhancedActive.value &&
                        !com.vrec.app.calls.CallMonitor.hasLiveCall() &&
                        AdbUserChoices.tcpMode(app) &&
                        !ExperimentalRecording.tcpPortListening()) {
                        switchToLocalTcp(app)
                    }
                }
            } finally { transition.unlock() }
        }
    }

    fun retry(context: Context) {
        val port = status.value.connectPort
        if (port != null) scope.launch {
            // Waits for an automatic attempt in flight, then re-checks state.
            transition.withLock {
                AdbLifecycle.coordinateSetup {
                    ExperimentalRecording.allowManualRetry()
                    connectAndSwitch(context.applicationContext, port, automatic = false)
                }
            }
        }
        else status.value = status.value.copy(
            message = "Enable Wireless debugging and return here. Listening for the Android service.")
    }

    fun openSettings(context: Context) {
        // The user is heading to Wireless debugging: search for its pairing service.
        start(context, userInitiated = true)
        // No public Wireless debugging action; highlight its row in Developer options.
        AdbSelfRecovery.openWirelessDebugging(context)
    }

    private fun showPairingNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            NOTIFICATION_CHANNEL, "VRec ADB pairing", NotificationManager.IMPORTANCE_HIGH))
        val target = Intent(context, VrecPairingReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 7601, target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder(VrecPairingReceiver.CODE_KEY)
            .setLabel("Six-digit pairing code")
            .build()
        val action = Notification.Action.Builder(
            android.R.drawable.ic_lock_lock, "Enter pairing code", pendingIntent)
            .addRemoteInput(input).build()
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("VRec · Android ADB pairing")
            .setContentText("Enter the code from Wireless debugging")
            .setOngoing(false).setAutoCancel(true).addAction(action).build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun stop(context: Context? = null) {
        context?.let {
            runCatching {
                (it.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NOTIFICATION_ID)
            }
        }
        val mgr = nsd
        if (mgr != null) {
            listOfNotNull(pairingDiscovery, connectDiscovery).forEach {
                runCatching { mgr.stopServiceDiscovery(it) }
            }
        }
        pairingDiscovery = null
        connectDiscovery = null
        nsd = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        attached = false
        searchSession++
        pairedInThisSession = false
        val phase = status.value.phase.let {
            if (it == Phase.SEARCHING || it == Phase.PAIRING) Phase.IDLE else it }
        status.value = status.value.copy(scanning = false, pairingPort = null, connectPort = null,
            phase = phase)
    }
}

/** RemoteInput callback carries only the user-entered pairing code. */
class VrecPairingReceiver : BroadcastReceiver() {
    companion object { const val CODE_KEY = "vrec_pairing_code" }
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CODE_KEY)?.toString()?.trim() ?: return
        LocalWirelessAdb.pair(context.applicationContext, code)
    }
}
