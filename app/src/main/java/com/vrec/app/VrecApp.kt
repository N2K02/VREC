package com.vrec.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.vrec.app.experimental.adb.AdbSelfRecovery
import kotlinx.coroutines.delay
import com.vrec.app.update.AppUpdater
import com.vrec.app.experimental.daemon.AdbHelperAutoRecovery
import com.vrec.app.experimental.adb.LocalWirelessAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VrecApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppUpdater.init(this, repo = "N2K02/VREC", appName = "VREC")
        // Re-discover a previously paired wireless endpoint after a process restart.
        if (com.vrec.app.experimental.adb.ExperimentalRecording.wirelessRecoveryAllowed(this) &&
            AdbSelfRecovery.onWifi(this))
            LocalWirelessAdb.start(this)
        // Reattach an existing helper at process start (also after boot, when
        // the accessibility service starts the process). If it was killed,
        // retry only a connection the user previously authorized. Wi-Fi is often
        // not up yet right after boot, so check again shortly after.
        // Background recovery runs in the app process (kept alive by the
        // accessibility service), not on Home: a quick check now, again after
        // Wi-Fi has had time to come up after boot, then every 2 minutes.
        // AdbHelperAutoRecovery's backoff limits real reconnect attempts.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            recover("process start")
            delay(45_000L)
            while (true) {
                recover("background check")
                delay(120_000L)
            }
        }
        watchWifiForRecovery()
    }

    private fun recover(reason: String) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { AdbHelperAutoRecovery.check(applicationContext) }
                .onFailure { Log.w("ADB helper recovery failed ($reason)", it) }
        }
    }

    /** Wireless restore needs Wi-Fi; retry recovery when Wi-Fi connects. Backoff limits attempts. */
    private fun watchWifiForRecovery() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (AdbSelfRecovery.canSelfRestore(this@VrecApp)) recover("Wi-Fi connected")
                }
            })
        }
    }
}
