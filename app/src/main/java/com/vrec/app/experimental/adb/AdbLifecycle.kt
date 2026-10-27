package com.vrec.app.experimental.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.experimental.daemon.AdbPrivilegedApiHelper
import com.vrec.app.experimental.daemon.ExperimentalCaptureDaemon
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns VRec's background ADB work so it can be stopped as one unit:
 * the periodic recovery loop, the Wi-Fi callback, wireless discovery and any
 * in-flight connect. Quit stops all of it (the process may stay alive for the
 * accessibility service); opening VRec again restarts it.
 */
object AdbLifecycle {
    @Volatile var quitting = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** Idempotent. Called at process start and when VRec is opened after Quit. */
    @Synchronized fun startBackground(context: Context) {
        val app = context.applicationContext
        quitting = false
        if (loop?.isActive != true) {
            // A quick check now, again once Wi-Fi has had time to come up after
            // boot, then every 2 minutes. Recovery's own backoff limits attempts.
            loop = scope.launch {
                recover(app, "process start")
                delay(45_000L)
                while (isActive) {
                    if (Prefs.recordingEnabled(app) &&
                        (!ExperimentalCaptureDaemon.ready.value ||
                            AdbPrivilegedApiHelper.state.value != AdbPrivilegedApiHelper.State.RUNNING)) {
                        recover(app, "background check")
                    }
                    delay(120_000L)
                }
            }
        }
        if (wifiCallback == null) {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!quitting && AdbSelfRecovery.canSelfRestore(app))
                        scope.launch { recover(app, "Wi-Fi connected") }
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            if (catchingNonCancellation { cm.registerNetworkCallback(request, callback) }.isSuccess)
                wifiCallback = callback
        }
    }

    private suspend fun recover(app: Context, reason: String) {
        if (quitting) return
        try {
            AdbHelperAutoRecovery.check(app)
        } catch (cancelled: CancellationException) {
            // Quit and superseded recovery jobs are expected cancellation, not failures.
            throw cancelled
        } catch (failure: Exception) {
            Log.w("ADB helper recovery failed ($reason)", failure)
        }
    }

    /**
     * Quit: stop recovery attempts, the Wi-Fi listener, wireless discovery and
     * in-flight connects, and close VRec's ADB session. Recording itself is
     * switched off by the caller first; the shell helper only stops capturing.
     */
    @Synchronized fun quit(context: Context) {
        val app = context.applicationContext
        quitting = true
        loop?.cancel()
        loop = null
        wifiCallback?.let { cb ->
            catchingNonCancellation {
                app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        wifiCallback = null
        LocalWirelessAdb.shutdown(app)
        CoroutineScope(Dispatchers.IO).launch {
            withContext(NonCancellable) { AdbTransport.close() }
            ExperimentalRecording.refreshConnectionStatus()
        }
        Log.i("Quit: background ADB recovery, discovery and listeners stopped")
    }
}
