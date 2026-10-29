package com.vrec.app.experimental.daemon

import java.io.InputStream
import java.security.MessageDigest

/**
 * PONG 2 includes the first eight SHA-256 hex digits of the shell JAR.
 * A compatible protocol version alone does not mean an older, still-running
 * daemon implements newer commands (such as the SIM source acknowledgement).
 */
internal object ShellHelperBuildPolicy {
    enum class Decision { CURRENT, STALE, UNKNOWN, INCOMPATIBLE_PROTOCOL }

    fun decide(banner: String?, expected: String): Decision {
        val parts = banner?.trim()?.split(Regex("\\s+")) ?: return Decision.INCOMPATIBLE_PROTOCOL
        if (parts.size < 2 || parts[0] != "PONG" || parts[1] != "2") {
            return Decision.INCOMPATIBLE_PROTOCOL
        }
        // Older VRec daemons answered PONG 2 without a build fingerprint.
        if (parts.size == 2) return Decision.STALE
        if (parts.size != 3 || !parts[2].matches(Regex("[a-fA-F0-9]{8}"))) {
            return Decision.UNKNOWN
        }
        if (!expected.matches(Regex("[a-f0-9]{8}"))) return Decision.UNKNOWN
        return if (parts[2].equals(expected, ignoreCase = true)) Decision.CURRENT
        else Decision.STALE
    }

    fun fingerprint(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().take(4).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
    }
}
