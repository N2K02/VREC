package com.vrec.app.experimental.daemon

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellJarStagingPolicyTest {
    @Test fun readableAppOwnedJarDoesNotCreatePublicTemporaryFile() {
        for (sdk in 26..37) {
            assertEquals(
                ShellJarStagingPolicy.Route.APP_EXTERNAL,
                ShellJarStagingPolicy.choose(appExternalReadableByShell = true, sdk = sdk),
            )
        }
    }

    @Test fun scopedStorageDenialUsesEphemeralDownloadOnSupportedAndroid() {
        for (sdk in 29..37) {
            assertEquals(
                ShellJarStagingPolicy.Route.MEDIASTORE_DOWNLOADS,
                ShellJarStagingPolicy.choose(appExternalReadableByShell = false, sdk = sdk),
            )
        }
    }

    @Test fun olderDeviceWithNoReadableTransferPathFailsExplicitly() {
        for (sdk in 26..28) {
            assertEquals(
                ShellJarStagingPolicy.Route.UNSUPPORTED,
                ShellJarStagingPolicy.choose(appExternalReadableByShell = false, sdk = sdk),
            )
        }
    }
}
