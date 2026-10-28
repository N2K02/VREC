package com.vrec.app.calls

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
import com.vrec.app.Log
import com.vrec.app.Prefs
import com.vrec.app.recording.CallDirection
import java.util.IdentityHashMap

/**
 * Non-UI Telecom observer. Requires an allowed android:manage_ongoing_calls AppOp on Android 12+.
 * It does NOT replace the dialer, handle call controls, or capture call audio.
 */
@RequiresApi(Build.VERSION_CODES.S)
class VrecInCallService : InCallService() {
    private val ids = IdentityHashMap<Call, String>()
    private val listeners = IdentityHashMap<Call, Call.Callback>()
    private var serial = 0L

    override fun onCreate() {
        super.onCreate()
        CallMonitor.onTelecomBound(true)
        Log.i("Telecom non-UI service connected")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (!Prefs.experimentalCallDetection(this)) return
        val id = "telecom-" + (++serial)
        ids[call] = id
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = publish(call)
            override fun onDetailsChanged(call: Call, details: Call.Details) = publish(call)
        }
        listeners[call] = callback
        call.registerCallback(callback)
        publish(call)
    }

    private fun publish(call: Call) {
        val id = ids[call] ?: return
        val details = runCatching { call.details }.getOrNull() ?: return
        val account = details.accountHandle
        val owner = account?.componentName?.packageName
        val telecom = getSystemService(TelecomManager::class.java)
        val cellular = owner == null || owner == "com.android.phone" ||
            owner == telecom?.defaultDialerPackage || owner == telecom?.systemDialerPackage
        val selfManaged = details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
        val flow = when (details.callDirection) {
            Call.Details.DIRECTION_INCOMING -> CallDirection.INCOMING
            Call.Details.DIRECTION_OUTGOING -> CallDirection.OUTGOING
            else -> CallDirection.UNKNOWN
        }
        val state = details.state
        val number = details.handle?.takeIf { it.scheme.equals("tel", true) }?.schemeSpecificPart
        val name = CallMetadataResolver.cleanName(details.contactDisplayName)
            ?: CallMetadataResolver.cleanName(details.callerDisplayName)
        CallMonitor.onTelecomEvent(
            this, id, state, owner, !cellular || selfManaged,
            flow, number, name, account?.id, details.connectTimeMillis.takeIf { it > 0L },
            videoCall = VideoProfile.isVideo(details.videoState),
        )
    }

    override fun onCallRemoved(call: Call) {
        val id = ids.remove(call)
        listeners.remove(call)?.let { call.unregisterCallback(it) }
        if (id != null) CallMonitor.onTelecomRemoved(this, id)
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        listeners.forEach { (call, listener) -> runCatching { call.unregisterCallback(listener) } }
        ids.keys.toList().forEach { call -> ids[call]?.let { CallMonitor.onTelecomRemoved(this, it) } }
        ids.clear()
        listeners.clear()
        CallMonitor.onTelecomBound(false)
        super.onDestroy()
    }
}
