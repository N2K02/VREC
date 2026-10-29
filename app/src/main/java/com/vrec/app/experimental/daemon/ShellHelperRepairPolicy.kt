package com.vrec.app.experimental.daemon

/**
 * Replacing an already-running helper is safe only through its authenticated
 * Q command, which refuses to exit during calls, recording, and imports.
 * A transient missing J acknowledgement is NOT permission for a background
 * restart. Explicit Start/Repair may attempt a single guarded replacement.
 */
internal object ShellHelperRepairPolicy {
    fun shouldRequestReplacement(
        build: ShellHelperBuildPolicy.Decision,
        incompatibleReply: Boolean,
        manualRepair: Boolean,
    ): Boolean =
        build == ShellHelperBuildPolicy.Decision.STALE ||
            incompatibleReply || manualRepair
}
