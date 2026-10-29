package com.vrec.app.experimental.daemon

/**
 * The authenticated shell helper's setup acknowledgements. Kept pure so
 * startup/reconnect behavior can be replayed in local JVM regression tests.
 *
 * The shell's E command sets recording/answered-call mode, and J configures
 * the SIM source. A reachable socket/PONG alone is not proof of readiness.
 */
internal object CaptureSetupProtocol {
    enum class Source { APPLIED, DEFERRED_DURING_CALL, INVALID, LEGACY_HELPER, UNCONFIRMED }

    data class Verdict(
        val ready: Boolean,
        val source: Source,
        val incompatible: Boolean,
        val detail: String,
    )

    /**
     * Scriptable bounded setup flow. The production helper and JVM tests both
     * use this runner, so a transient first acknowledgement doesn't make a
     * previously working recording helper appear permanently broken.
     */
    suspend fun verifyWithRetry(
        maxAttempts: Int = 3,
        configure: suspend () -> Boolean,
        incompatible: () -> Boolean,
        waitBetween: suspend (Int) -> Unit,
    ): Boolean {
        require(maxAttempts in 1..5)
        for (attempt in 1..maxAttempts) {
            if (configure()) return true
            if (incompatible()) return false
            if (attempt < maxAttempts) waitBetween(attempt)
        }
        return false
    }

    /** Off=0, Automatic=1, On-demand=2; the Home master switch always wins. */
    fun selectedMode(recordingEnabled: Boolean, automatic: Boolean): Int = when {
        !recordingEnabled -> 0
        automatic -> 1
        else -> 2
    }

    fun modeAccepted(reply: String?, enabled: Boolean, waitForAnswer: Boolean): Boolean =
        modeAcceptedMode(reply, if (enabled) 1 else 0, waitForAnswer)

    fun modeAcceptedMode(reply: String?, mode: Int, waitForAnswer: Boolean): Boolean {
        if (mode !in 0..2) return false
        val wait = if (mode == 1 && waitForAnswer) 1 else 0
        return reply == "$mode WAIT $wait" || (wait == 0 && reply == mode.toString())
    }

    fun classifySource(reply: String?, requested: Int): Source = when (reply) {
        "SOURCE $requested" -> Source.APPLIED
        "SOURCE BUSY" -> Source.DEFERRED_DURING_CALL
        "SOURCE INVALID" -> Source.INVALID
        "SOURCE UNSUPPORTED" -> Source.LEGACY_HELPER
        else -> Source.UNCONFIRMED
    }

    fun evaluate(
        modeReply: String?, sourceReply: String?, enabled: Boolean,
        waitForAnswer: Boolean, requestedSource: Int,
    ): Verdict = evaluateMode(modeReply, sourceReply, if (enabled) 1 else 0,
        waitForAnswer, requestedSource)

    fun evaluateMode(
        modeReply: String?, sourceReply: String?, mode: Int,
        waitForAnswer: Boolean, requestedSource: Int,
    ): Verdict {
        if (!modeAcceptedMode(modeReply, mode, waitForAnswer)) {
            return Verdict(
                ready = false, source = Source.UNCONFIRMED,
                incompatible = !modeReply.isNullOrBlank(),
                detail = if (modeReply.isNullOrBlank()) "Recording mode acknowledgement timed out"
                    else "Recording mode acknowledgement was incompatible",
            )
        }
        return when (val source = classifySource(sourceReply, requestedSource)) {
            Source.APPLIED -> Verdict(true, source, false, "Recording mode and SIM source verified")
            Source.DEFERRED_DURING_CALL -> Verdict(
                true, source, false, "Recording mode verified; SIM source change deferred until call ends",
            )
            Source.INVALID -> Verdict(false, source, true, "Shell rejected the requested SIM source")
            Source.LEGACY_HELPER -> Verdict(false, source, true,
                "Running shell helper does not support SIM source configuration; safe upgrade required")
            Source.UNCONFIRMED -> Verdict(
                false, source, !sourceReply.isNullOrBlank(),
                if (sourceReply.isNullOrBlank()) "SIM source acknowledgement timed out"
                    else "SIM source acknowledgement was incompatible",
            )
        }
    }
}
