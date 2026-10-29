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
                "Authorize an Android ADB shell UID 2000 connection first."))
        }
        return runCatching {
            // Telecom checks a UID-scoped AppOp on recent Android versions.
            // Use one scope consistently; setting both UID and package modes
            // can yield conflicting persistent permissions on some OEM builds.
            val command = "cmd appops set --uid ${context.packageName} MANAGE_ONGOING_CALLS allow"
            val output = AdbTransport.exec(command).getOrThrow()
            repeat(6) {
                if (isGranted(context)) return@runCatching Unit
                delay(300L)
            }
            error(
                "Android did not confirm UID-scoped MANAGE_ONGOING_CALLS access. " +
                    "This optional Telecom permission may be unavailable on this device. " +
                    "Shell response: " + output.take(180))
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
