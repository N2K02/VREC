package com.vrec.app.calls

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Read-only status. VREC cannot grant this elevated AppOp from its ordinary app process. */
object CallDetectionAccess {
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            manager.checkOpNoThrow(
                "android:manage_ongoing_calls", Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
