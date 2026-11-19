package com.vrec.app.update

import org.junit.Assert.*
import org.junit.Test

class TrackedUpdatePolicyTest {
    @Test fun stableAndExperimentalNamesAndUrlsMatchTheSelectedArchitecture() {
        for (track in listOf("adb", "shizuku")) {
            val other = if (track == "adb") "shizuku" else "adb"
            val stableTag = "$track-v8.2"
            val experimentalTag = "experimental-$track-pr-11-run-22"
            val stable = "VREC-${track.uppercase()}-8.2.apk"
            val experimental = "VREC-${track.uppercase()}-8.3-experimental.4.apk"
            fun accepted(tag: String, version: String?, file: String, repo: String = "N2K02/VREC") =
                TrackedUpdatePolicy.matchesAsset("N2K02/VREC", tag, track, version,
                    file, "https://github.com/$repo/releases/download/$tag/$file")
            assertTrue(accepted(stableTag, "8.2", stable))
            assertTrue(accepted(experimentalTag, null, experimental))
            assertFalse(accepted(stableTag, "8.1", stable))
            assertFalse(accepted(stableTag, "8.2", "$stable.apk"))
            assertFalse(accepted(stableTag, "8.2", stable, "example/VREC"))
            assertFalse(accepted(experimentalTag, null, "VREC-${other.uppercase()}-8.3-experimental.4.apk"))
            assertFalse(accepted(experimentalTag, null, stable))
        }
    }

    @Test fun rejectsMissingOrAmbiguousMetadataAndMismatchedSigning() {
        assertEquals(123L, TrackedUpdatePolicy.versionCode("Version code: 123"))
        assertNull(TrackedUpdatePolicy.versionCode("Version code: 123\nVersion code: 124"))
        assertNull(TrackedUpdatePolicy.versionCode("Version code: 0"))
        assertEquals("a".repeat(64), TrackedUpdatePolicy.digest("sha256:" + "A".repeat(64)))
        assertNull(TrackedUpdatePolicy.digest("a".repeat(64)))
        assertFalse(TrackedUpdatePolicy.acceptsSigningUpdate(listOf("a"), emptyList(), emptyList()))
        assertFalse(TrackedUpdatePolicy.acceptsSigningUpdate(listOf("a", "b"), listOf("a", "c"), emptyList()))
        assertFalse(TrackedUpdatePolicy.acceptsSigningUpdate(listOf("b"), listOf("a"), listOf("a")))
        assertTrue(TrackedUpdatePolicy.acceptsSigningUpdate(listOf("a"), listOf("b"), listOf("a", "b")))
    }
}
