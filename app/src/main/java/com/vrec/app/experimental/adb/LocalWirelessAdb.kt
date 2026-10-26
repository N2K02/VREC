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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import com.vrec.app.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    data class State(
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
    @Volatile private var attached = false

    fun start(context: Context) {
        val app = context.applicationContext
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
        status.value = State(scanning = true,
            paired = ExperimentalRecording.wirelessWasPaired(app),
            message = "Searching for this phone's Wireless debugging service…")
        pairingDiscovery = discover(app, service, "_adb-tls-pairing._tcp", true)
        connectDiscovery = discover(app, service, "_adb-tls-connect._tcp", false)
    }

    private fun discover(
        app: Context, nsdManager: NsdManager, type: String, pairing: Boolean,
    ): NsdManager.DiscoveryListener {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                status.value = status.value.copy(scanning = false,
                    message = "Wireless discovery unavailable ($code). Use USB bootstrap or open Developer options.")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceLost(info: NsdServiceInfo) {
                // Service may stop when the Android pairing-code dialog closes.
                status.value = if (pairing) status.value.copy(pairingPort = null)
                    else status.value.copy(connectPort = null)
            }
            @Suppress("DEPRECATION")
            override fun onServiceFound(info: NsdServiceInfo) {
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        val host = service.host ?: return
                        // Don't accept another device found on a shared Wi-Fi LAN.
                        if (!belongsToThisPhone(host) || service.port !in 1..65535) return
                        val port = service.port
                        if (pairing) {
                            status.value = status.value.copy(pairingPort = port, scanning = false,
                                message = "Pairing code detected. Enter the six-digit code from Android.")
                            showPairingNotification(app)
                        } else {
                            status.value = status.value.copy(connectPort = port, scanning = false,
                                message = "This phone's Wireless debugging is available.",
                                paired = ExperimentalRecording.wirelessWasPaired(app))
                            if (ExperimentalRecording.wirelessWasPaired(app) &&
                                !ExperimentalRecording.awaitingManualRetry &&
                                !ExperimentalRecording.status.value.connected) {
                                scope.launch {
                                    val result = ExperimentalRecording.connectWireless(app, port, automatic = true)
                                    if (result.isSuccess) status.value =
                                        status.value.copy(message = "Wireless ADB helper is ready.")
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
        status.value = status.value.copy(pairing = true, message = "Pairing with Android…")
        scope.launch {
            val outcome = withTimeoutOrNull(30_000L) {
                AdbTransport.pairLocal(app, pairingPort, code)
            } ?: Result.failure(IllegalStateException("Pairing timed out"))
            // Never retain or log pairing code.
            status.value = if (outcome.isSuccess) status.value.copy(
                pairing = false, paired = true,
                message = "Paired. Connecting to the Android shell…")
            else status.value.copy(
                pairing = false, message = "Pairing failed: ${outcome.exceptionOrNull()?.message}")
            if (outcome.isSuccess) {
                val port = status.value.connectPort
                if (port != null) {
                    val result = ExperimentalRecording.connectWireless(app, port)
                    status.value = status.value.copy(message = if (result.isSuccess)
                        "Wireless ADB shell UID 2000 verified; helper ready."
                    else "Paired, but could not connect. Retry from Home.")
                } else {
                    status.value = status.value.copy(message =
                        "Paired successfully. Waiting for Android Wireless debugging connection service…")
                }
            }
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIFICATION_ID)
        }
    }

    fun retry(context: Context) {
        val port = status.value.connectPort
        if (port != null) scope.launch {
            val result = ExperimentalRecording.connectWireless(context.applicationContext, port)
            status.value = status.value.copy(message = if (result.isSuccess)
                "Wireless ADB verified; recording helper ready."
            else "Wireless ADB connect failed: ${result.exceptionOrNull()?.message}")
        }
        else status.value = status.value.copy(
            message = "Enable Wireless debugging and return here. Listening for the Android service.")
    }

    fun openSettings(context: Context) {
        runCatching {
            // Android has no public stable per-device Wireless debugging Settings action.
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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

    fun stop() {
        val mgr = nsd
        if (mgr != null) {
            listOfNotNull(pairingDiscovery, connectDiscovery).forEach {
                runCatching { mgr.stopServiceDiscovery(it) }
            }
        }
        pairingDiscovery = null
        connectDiscovery = null
        nsd = null
        attached = false
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
