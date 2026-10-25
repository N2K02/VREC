package com.vrec.app.shell;

/** Plain Java so shell-server state regression tests run without Android SDK. */
public final class AnsweredCallGateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        AnsweredCallGate gate = new AnsweredCallGate();
        gate.begin(false, false, true, false);
        check(gate.waiting(), "Outgoing dialing must not start a recording");
        check(gate.confirm(), "First Telecom answer must unlock the recording");
        check(!gate.waiting(), "Connected call should not remain pending");
        check(!gate.confirm(), "Repeated ACTIVE events must not start duplicates");
        gate.reset();
        check(!gate.confirm(), "An answer after hangup must do nothing");

        gate.begin(true, false, true, false);
        check(!gate.waiting(), "An answered incoming call should start immediately");

        gate.begin(false, false, false, false);
        check(!gate.waiting(), "Without a Telecom observer retain legacy OFFHOOK fallback");

        gate.begin(false, true, true, false);
        check(!gate.waiting(), "VoIP calls keep their own connected-state source");

        gate.begin(false, false, true, true);
        check(!gate.waiting(), "Early Telecom ACTIVE is valid when OFFHOOK arrives later");
        System.out.println("AnsweredCallGate regression tests passed");
    }
}
