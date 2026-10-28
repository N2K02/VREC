package com.vrec.app.experimental.daemon

/**
 * The authenticated shell helper's setup acknowledgements. Kept pure so
 * startup/reconnect behavior can be replayed in local JVM regression tests.
 *
 * The shell's E command sets recording/answered-call mode, and Y configures
 * the SIM source. A reachable socket/PONG alone is not proof of readiness.
 */
internal object CaptureSetupProtocol {
    enum class Source { APPLIED, DEFERRED_DURING_CALL, INVALID, UNCONFIRMED }

    data class Verdict(
        val ready: Boolean,
        val source: Source,
        val incompatible: Boolean,
        val detail: String,
    )

    fun modeAccepted(reply: String?, enabled: Boolean, waitForAnswer: Boolean): Boolean {
        val mode = if (enabled) 1 else 0
        val wait = if (enabled && waitForAnswer) 1 else 0
        return reply == "$mode WAIT $wait" || (!waitForAnswer && reply == mode.toString())
    }

    fun classifySource(reply: String?, requested: Int): Source = when (reply) {
        "SOURCE $requested" -> Source.APPLIED
        "SOURCE BUSY" -> Source.DEFERRED_DURING_CALL
        "SOURCE INVALID" -> Source.INVALID
        else -> Source.UNCONFIRMED
    }

    fun evaluate(
        modeReply: String?, sourceReply: String?, enabled: Boolean,
        waitForAnswer: Boolean, requestedSource: Int,
    ): Verdict {
        if (!modeAccepted(modeReply, enabled, waitForAnswer)) {
            return Verdict(
                ready = false, source = Source.UNCONFIRMED,
                incompatible = !modeReply.isNullOrBlank(),
                detail = if (modeReply.isNullOrBlank()) "Recording mode acknowledgement timed out"
                    else "Recording mode acknowledgement was incompatible",
            )
        }
        return when (val source = sourceReply(sourceReply, requestedSource)) {
            Source.APPLIED -> Verdict(true, source, false, "Recording mode and SIM source verified")
            Source.DEFERRED_DURING_CALL -> Verdict(
                true, source, false, "Recording mode verified; SIM source change deferred until call ends",
            )
            Source.INVALID -> Verdict(false, source, true, "Shell rejected the requested SIM source")
            Source.UNCONFIRMED -> Verdict(
                false, source, !sourceReply.isNullOrBlank(),
                if (sourceReply.isNullOrBlank()) "SIM source acknowledgement timed out"
                    else "SIM source acknowledgement was incompatible",
            )
        }
    }
}
