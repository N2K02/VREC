package com.vrec.app.experimental.daemon

import kotlinx.coroutines.CancellationException

/**
 * One ordered startup transaction for every entry point (Home Start, USB/TCP,
 * Wireless pairing, manual repair, and recovery). The shell must be verified
 * live, but optional Android permission denials must never suppress capture.
 *
 * An isolated Steps implementation makes the real ordering executable in JVM
 * tests without an ADB daemon or Android device.
 */
internal object AdbHelperStartFlow {
    enum class Stage { VERIFY_SHELL, LAUNCH, PROBE }
    sealed interface Outcome {
        data class Ready(val unavailablePermissions: List<String>) : Outcome
        data class Failed(val stage: Stage, val reason: String) : Outcome
    }

    interface Steps {
        suspend fun verifyUid2000(): Boolean
        suspend fun configurePermissions(): List<String>
        suspend fun ensureHelperStarted(): Result<Unit>
        suspend fun probeHelper(): Boolean
    }

    suspend fun run(steps: Steps): Outcome {
        val authorized = try {
            steps.verifyUid2000()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!authorized) return Outcome.Failed(
            Stage.VERIFY_SHELL, "ADB shell UID 2000 is not currently verified. Reconnect or repair ADB."
        )

        // Telecom AppOps, restricted call logs, and optional runtime grants may be
        // unavailable on some Android builds. Capture must still get a chance to start.
        val unavailable = try {
            steps.configurePermissions()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            listOf("Permission setup incomplete: ${problem.message ?: problem.javaClass.simpleName}")
        }
        val start = try {
            steps.ensureHelperStarted()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            Result.failure(problem)
        }
        if (start.isFailure) return Outcome.Failed(
            Stage.LAUNCH, start.exceptionOrNull()?.message ?: "Shell helper launch failed."
        )

        val answering = try {
            steps.probeHelper()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!answering) return Outcome.Failed(
            Stage.PROBE, "Shell helper launched but did not answer the authenticated health check."
        )
        return Outcome.Ready(unavailable)
    }
}
