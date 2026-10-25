package com.vrec.app.shell;

/**
 * Outgoing OFFHOOK means dialing. Require a distinct connected event only
 * while the app's privileged Telecom observer is enabled and authorized.
 * Incoming OFFHOOK and devices without that observer keep the legacy start.
 */
final class AnsweredCallGate {
    private boolean waiting;

    void begin(boolean incoming, boolean voip, boolean preciseAnswerAvailable,
               boolean alreadyConfirmed) {
        waiting = !incoming && !voip && preciseAnswerAvailable && !alreadyConfirmed;
    }

    boolean waiting() {
        return waiting;
    }

    /** Idempotent. Only the first valid answer can start a recording. */
    boolean confirm() {
        if (!waiting) return false;
        waiting = false;
        return true;
    }

    void reset() {
        waiting = false;
    }
}
