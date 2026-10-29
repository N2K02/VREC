package com.vrec.app.experimental.adb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real foreground Start and permission-card repair share this decision,
 * regardless of which screen the user entered from.
 */
class AdbForegroundSetupRoutingTest {
    @Test fun pairedWirelessPortRetriesWithoutUnnecessaryUsbBootstrap() {
        assertEquals(
            AdbForegroundSetup.Routing.Next.RETRY_PAIRED_WIRELESS,
            AdbForegroundSetup.Routing.next(connectPort = 37121, paired = true),
        )
    }

    @Test fun rememberedPairingCanUseAnAvailableWirelessPort() {
        assertEquals(
            AdbForegroundSetup.Routing.Next.RETRY_PAIRED_WIRELESS,
            AdbForegroundSetup.Routing.next(connectPort = 65535, paired = true),
        )
    }

    @Test fun firstTimePairingWithoutCodeMustDiscoverService() {
        assertEquals(
            AdbForegroundSetup.Routing.Next.RESTORE_HELPER_OR_TCP,
            AdbForegroundSetup.Routing.next(connectPort = null, paired = false),
        )
    }

    @Test fun pairingIsNotAssumedForUnrecognizedWirelessEndpoint() {
        assertEquals(
            AdbForegroundSetup.Routing.Next.RESTORE_HELPER_OR_TCP,
            AdbForegroundSetup.Routing.next(connectPort = 37001, paired = false),
        )
    }

    @Test fun pairedButWirelessPortMissingPrefersExistingHelperOrTcp() {
        assertEquals(
            AdbForegroundSetup.Routing.Next.RESTORE_HELPER_OR_TCP,
            AdbForegroundSetup.Routing.next(connectPort = null, paired = true),
        )
    }
}
