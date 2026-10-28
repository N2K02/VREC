package com.vrec.app.experimental.daemon

/**
 * The helper refuses SIM audio-source changes while a call is in progress.
 * SOURCE BUSY proves the authenticated helper is alive and protecting capture;
 * it must not be mistaken for a crash or trigger a daemon replacement.
 */
internal object ShellSourceConfigPolicy {
    fun isHealthyAcknowledgement(reply: String): Boolean =
        reply == "SOURCE BUSY" || reply in setOf(
            "SOURCE 0", "SOURCE 1", "SOURCE 2", "SOURCE 3"
        )
}
