package com.vrec.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.n2k02.adb.core.AdbHomeSetupPage
import dev.n2k02.adb.core.AdbPageLifecycle
import com.vrec.app.calls.VrecAccessibilityService
import com.vrec.app.experimental.adb.SharedAdbHostAdapter

/** Canonical core Home setup page: automatic pairing only, no entered code or notification fallback. */
@Composable
fun WirelessAdbSetupCard() {
    val context = LocalContext.current
    val core = remember(context.applicationContext) { SharedAdbHostAdapter.core(context) }
    AdbPageLifecycle(core)
    AdbHomeSetupPage(
        adb = core,
        accessibilityEnabled = VrecAccessibilityService.isEnabled(context),
        onOpenAccessibility = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    )
}
