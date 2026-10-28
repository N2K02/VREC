package com.vrec.app.experimental.adb

/**
 * Pure first-time/recovery routing. A UI revisit must never be an input:
 * only explicit setup and connection-state transitions can advance onboarding.
 */
object AdbOnboardingPolicy {
    enum class Step {
        READY, USE_EXISTING_TCP, VERIFY_SHELL, DISCOVER_WIRELESS,
        WAIT_FOR_PAIRING_CODE, CONNECT_PAIRED_WIRELESS, SWITCH_TO_TCP,
        START_HELPER, MANUAL_RETRY
    }

    data class Signals(
        val helperHealthy: Boolean,
        val tcpListening: Boolean,
        val shellUid: Int?,
        val paired: Boolean,
        val pairingPortFound: Boolean,
        val wirelessPortFound: Boolean,
        val tcpEnabled: Boolean,
        val awaitingManualRetry: Boolean,
    )

    fun next(s: Signals): Step = when {
        s.helperHealthy -> Step.READY
        s.awaitingManualRetry -> Step.MANUAL_RETRY
        s.shellUid == 2000 && s.tcpEnabled && !s.tcpListening -> Step.SWITCH_TO_TCP
        s.shellUid == 2000 -> Step.START_HELPER
        s.tcpListening -> Step.USE_EXISTING_TCP
        s.paired && s.wirelessPortFound -> Step.CONNECT_PAIRED_WIRELESS
        s.pairingPortFound -> Step.WAIT_FOR_PAIRING_CODE
        else -> Step.DISCOVER_WIRELESS
    }
}
