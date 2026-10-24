package com.vrec.app.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit Record-again action from VRec's non-exported call-control notification. */
class CallControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RECORD_AGAIN = "com.vrec.app.action.RECORD_AGAIN"
        const val EXTRA_SESSION_ID = "session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RECORD_AGAIN) {
            CallMonitor.userRecordAgain(context, intent.getStringExtra(EXTRA_SESSION_ID))
        }
    }
}
