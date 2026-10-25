package com.vrec.app.calls

/** OFFHOOK means 'dialing' for an outgoing SIM call, not 'answered'. */
internal object OutgoingCallStartPolicy {
    fun waitForTelecomAnswer(incomingRang: Boolean, telecomObserverBound: Boolean,
                             advancedDetectionAvailable: Boolean): Boolean =
        !incomingRang && telecomObserverBound && advancedDetectionAvailable
}
