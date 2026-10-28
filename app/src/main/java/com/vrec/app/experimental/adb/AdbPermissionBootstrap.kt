package com.vrec.app.experimental.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.vrec.app.calls.CallDetectionAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-tap best-effort permission configuration only AFTER real adbd UID 2000.
 * Runtime grants are verified with Android PackageManager; shell output alone
 * is not proof. Restricted permissions may be denied by installer policy.
 * Does not enable Accessibility, notification listeners or special settings.
 */
object AdbPermissionBootstrap {
    data class ResultSummary(
        val granted: List<String> = emptyList(),
        val unavailable: List<String> = emptyList(),
        val busy: Boolean = false,
        val finished: Boolean = false,
    )
    val status = MutableStateFlow(ResultSummary())
    /** Serializes grant runs: overlapping connects (USB, wireless, recovery) can't interleave pm grant. */
    private val mutex = Mutex()

    suspend fun grantEligible(context: Context): ResultSummary = mutex.withLock {
        grantLocked(context.applicationContext)
    }

    private suspend fun grantLocked(app: Context): ResultSummary {
        if (!ExperimentalRecording.status.value.connected ||
            ExperimentalRecording.status.value.shellUid != 2000 ||
            !AdbTransport.isConnected) {
            return ResultSummary(unavailable = listOf("ADB shell UID 2000 not authorized"))
                .also { status.value = it }
        }
        // The cached status can lag a dropped transport: re-verify on the live shell
        // immediately before any privileged command.
        val live = AdbTransport.exec("id -u", timeoutMs = 3_000).getOrNull()
            ?.lineSequence()?.any { it.trim() == "2000" } == true
        if (!live) {
            return ResultSummary(unavailable = listOf("ADB shell UID 2000 not re-verified"))
                .also { status.value = it }
        }
        status.value = ResultSummary(busy = true)
        val permissions = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CONTACTS)
            // Android may require installer allowlisting: never treat failure as success.
            add(Manifest.permission.READ_CALL_LOG)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val granted = mutableListOf<String>()
        val unavailable = mutableListOf<String>()
        for (permission in permissions) {
            val short = permission.substringAfterLast('.')
            if (app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                granted.add(short)
                continue
            }
            catchingNonCancellation {
                AdbTransport.exec("pm grant ${app.packageName} $permission", timeoutMs = 10_000)
                    .getOrThrow()
            }
            if (app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                granted.add(short)
            } else {
                val instruction = when (permission) {
                    Manifest.permission.READ_CALL_LOG ->
                        "restricted call-log access: check Android App info > Permissions; some installers require allowlisting"
                    Manifest.permission.READ_PHONE_STATE ->
                        "phone access: approve in Android App info > Permissions"
                    Manifest.permission.READ_CONTACTS ->
                        "contacts access: approve in Android App info > Permissions"
                    Manifest.permission.POST_NOTIFICATIONS ->
                        "notifications: approve in Android App info > Notifications"
                    else -> "Android requires manual permission approval"
                }
                unavailable.add("$short ($instruction)")
            }
        }
        // Only for the user's opt-in "Restore after restart"; never requested otherwise.
        if (Build.VERSION.SDK_INT >= 30 && AdbUserChoices.restoreAfterRestart(app)) {
            if (catchingNonCancellation { AdbSelfRecovery.grantSecureSettings(app) }.getOrDefault(false))
                granted.add("Automatic restore after restart")
            else unavailable.add("Automatic restore after restart (Android denied the grant)")
        }
        val telecom = catchingNonCancellation { ExperimentalRecording.grantAdvancedDetection(app) }
            .getOrElse { Result.failure(it) }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            granted.add("Advanced call detection not required on this Android version")
        } else if (telecom.isSuccess && CallDetectionAccess.isGranted(app)) {
            granted.add("Advanced call detection")
        } else {
            unavailable.add("Advanced call detection (requires supported Telecom access)")
        }
        // Accessibility, notification listeners, and overlay access remain
        // user-controlled; ADB permission grants do not establish these roles.
        return ResultSummary(granted, unavailable, finished = true).also { status.value = it }
    }
}
