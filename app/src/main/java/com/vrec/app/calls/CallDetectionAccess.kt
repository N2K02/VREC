package com.vrec.app.calls

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.vrec.app.experimental.adb.AdbTransport
import com.vrec.app.experimental.adb.ExperimentalRecording
import kotlinx.coroutines.delay

/** Read-only status. VREC cannot grant this elevated AppOp from its ordinary app process. */
object CallDetectionAccess {
    /**
     * User-initiated special-access grant using VRec's verified local ADB session.
     * Never run on merely selecting the recording method. Verify the real AppOp
     * afterwards instead of trusting shell output (ADB exec has no exit code).
     */
    suspend fun grantWithAdb(context: Context): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Result.failure(IllegalStateException("Advanced call detection needs Android 12+."))
        }
        val status = ExperimentalRecording.status.value
        if (!status.connected || status.shellUid != 2000) {
            return Result.failure(IllegalStateException(
                "Connect USB-bootstrapped localhost TCP/5555 first to authorize the special access."))
        }
        return runCatching {
            val command = "appops set ${context.packageName} android:manage_ongoing_calls allow"
            val output = AdbTransport.exec(command).getOrThrow()
            repeat(4) {
                if (isGranted(context)) return@runCatching Unit
                delay(250L)
            }
            error(
                "Android did not confirm special access. " +
                "Check the ADB connection or use the manual command. " +
                output.take(180))
        }
    }

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
