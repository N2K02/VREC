package com.vrec.app.shell;

/** Plain Java so shell-server state regression tests run without Android SDK. */
public final class AnsweredCallGateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        AnsweredCallGate gate = new AnsweredCallGate();
        gate.begin(false, false, true);
        check(gate.waiting(), "Outgoing dialing must not start a recording");
        check(gate.confirm(), "First Telecom answer must unlock the recording");
        check(!gate.waiting(), "Connected call should not remain pending");
        check(!gate.confirm(), "Repeated ACTIVE events must not start duplicates");
        gate.reset();
        check(!gate.confirm(), "An answer after hangup must do nothing");

        gate.begin(true, false, true);
        check(!gate.waiting(), "An answered incoming call should start immediately");

        gate.begin(false, false, false);
        check(!gate.waiting(), "Without a Telecom observer retain legacy OFFHOOK fallback");

        gate.begin(false, true, true);
        check(!gate.waiting(), "VoIP calls keep their own connected-state source");

        // A premature confirmation is ignored. The app retries while the
        // SAME observed Telecom call remains active; no stale next-call hint.
        gate.reset();
        check(!gate.confirm(), "Answer before OFFHOOK must not unlock a future call");
        gate.begin(false, false, true);
        check(gate.waiting(), "Next call must still wait for its own answer");
        check(gate.confirm(), "A retry after OFFHOOK should start exactly once");
        gate.retryAfterFailedStart();
        check(gate.waiting(), "Failed audio initialization allows an ACTIVE retry");
        check(gate.confirm(), "Retry should unlock the same answered call");
        System.out.println("AnsweredCallGate regression tests passed");
    }
}
