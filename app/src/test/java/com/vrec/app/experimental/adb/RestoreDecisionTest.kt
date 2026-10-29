package com.vrec.app.experimental.adb

import com.vrec.app.experimental.adb.RestoreDecision.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reboot recovery, explicit disablement, revoked authorization and opt-out. */
class RestoreDecisionTest {
    private val rebooted = RestoreDecision.Inputs(
        optedIn = true, sdk = 34, paired = true, autoRecoveryAllowed = true,
        hasSecureSettings = true, onWifi = true, wirelessDebuggingOn = false,
        currentBoot = 12, lastVerifiedBoot = 11, awaitingManualRetry = false,
    )

    @Test fun rebootWithEverythingInPlaceRestores() =
        assertEquals(Verdict.RESTORE, RestoreDecision.decide(rebooted))

    @Test fun userTurnedWirelessDebuggingOffInSameBootIsRespected() =
        assertEquals(Verdict.USER_TURNED_OFF, RestoreDecision.decide(rebooted.copy(lastVerifiedBoot = 12)))

    @Test fun unknownBootCountNeverOverridesTheUser() {
        assertEquals(Verdict.USER_TURNED_OFF, RestoreDecision.decide(rebooted.copy(currentBoot = -1)))
        assertEquals(Verdict.USER_TURNED_OFF, RestoreDecision.decide(rebooted.copy(lastVerifiedBoot = -1)))
    }

    @Test fun notOptedInNeverRestores() =
        assertEquals(Verdict.NOT_OPTED_IN, RestoreDecision.decide(rebooted.copy(optedIn = false)))

    @Test fun deliberateDisconnectBlocksRestore() =
        assertEquals(Verdict.DISCONNECTED_BY_USER, RestoreDecision.decide(rebooted.copy(autoRecoveryAllowed = false)))

    @Test fun revokedOrDeniedAuthorizationWaitsForManualRetry() =
        assertEquals(Verdict.MANUAL_RETRY_REQUIRED, RestoreDecision.decide(rebooted.copy(awaitingManualRetry = true)))

    @Test fun missingPrerequisites() {
        assertEquals(Verdict.NOT_SUPPORTED, RestoreDecision.decide(rebooted.copy(sdk = 29)))
        assertEquals(Verdict.NOT_PAIRED, RestoreDecision.decide(rebooted.copy(paired = false)))
        assertEquals(Verdict.NO_PERMISSION, RestoreDecision.decide(rebooted.copy(hasSecureSettings = false)))
        assertEquals(Verdict.NO_WIFI, RestoreDecision.decide(rebooted.copy(onWifi = false)))
    }

    @Test fun alreadyOnOnlyReconnectsWithoutChangingTheSetting() =
        assertEquals(Verdict.ALREADY_ON, RestoreDecision.decide(rebooted.copy(wirelessDebuggingOn = true)))

    @Test fun restoreFailureOutcomesMapWithoutTouchingTheSetting() {
        assertEquals(AdbSelfRecovery.RestoreOutcome.USER_DISABLED, AdbSelfRecovery.outcomeFor(Verdict.USER_TURNED_OFF))
        assertEquals(AdbSelfRecovery.RestoreOutcome.MANUAL_RETRY_REQUIRED,
            AdbSelfRecovery.outcomeFor(Verdict.MANUAL_RETRY_REQUIRED))
        assertEquals(AdbSelfRecovery.RestoreOutcome.NOT_ELIGIBLE, AdbSelfRecovery.outcomeFor(Verdict.NOT_OPTED_IN))
        assertEquals(null, AdbSelfRecovery.outcomeFor(Verdict.RESTORE))
        assertEquals(null, AdbSelfRecovery.outcomeFor(Verdict.ALREADY_ON))
    }
}
