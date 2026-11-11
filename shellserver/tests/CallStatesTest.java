package com.vrec.app.shell;

/**
 * Regression coverage for the actual shell-side SIM call lifecycle.
 * This pure Java test runs without an Android device.
 */
public final class CallStatesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Effects implements CallStates.Effects {
        int started;
        int ended;
        int scheduled;
        int cancelled;
        int continued;
        boolean incoming;
        boolean audioInCall;
        long lastDelay;

        @Override public void callStarted(boolean incoming) {
            started++;
            this.incoming = incoming;
        }
        @Override public void callEnded() { ended++; }
        @Override public void scheduleEndCheck(long delayMs) {
            scheduled++;
            lastDelay = delayMs;
        }
        @Override public void cancelEndCheck() { cancelled++; }
        @Override public boolean audioInCall() { return audioInCall; }
        @Override public void callGoesOn() { continued++; }
        @Override public void log(String message) { }
    }

    private static void startupIdleDoesNotFinalizeOrCreateARecording() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.idle();
        states.endCheck();
        check(e.started == 0 && e.ended == 0 && e.scheduled == 0,
                "initial IDLE must have no side effects");
    }

    private static void declinedIncomingDoesNotContaminateNextCall() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.ringing();
        states.idle();
        check(!states.rang() && !states.inCall(), "missed ring must be cleared");
        states.offHook();
        check(e.started == 1 && !e.incoming,
                "next outgoing call must not inherit the previous incoming ring");
    }

    private static void transientIdleNeverCutsActiveCall() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.ringing();
        states.offHook();
        check(states.inCall() && e.incoming, "incoming off-hook should start");
        e.audioInCall = true;
        states.idle();
        check(e.scheduled == 1 && e.lastDelay == CallStates.END_CHECK_MS,
                "IDLE must schedule a delayed confirmation");
        states.endCheck();
        states.endCheck();
        check(states.inCall() && e.ended == 0 && e.continued == 2,
                "audio mode still in-call must prevent premature stop");
        check(e.scheduled == 3, "should recheck until audio mode exits in-call");
        e.audioInCall = false;
        states.endCheck();
        check(!states.inCall() && e.ended == 1, "final IDLE should end once");
        states.endCheck();
        check(e.ended == 1, "stale timer must never double-finalize");
    }

    private static void anotherCallCancelsPendingEnd() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.offHook();
        states.idle();
        states.ringing();
        states.offHook();
        check(e.cancelled == 1, "new off-hook cancels pending stop");
        states.endCheck();
        check(e.ended == 0 && states.inCall(),
                "stale end callback cannot terminate new active call");
        check(e.started == 2 && e.incoming,
                "new incoming call retains its own direction");
    }

    private static void ringingDuringPendingConfirmationRemainsCallScoped() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.offHook();
        states.idle();
        states.ringing();
        check(states.rang(), "new ringing event should be retained");
        e.audioInCall = false;
        states.endCheck();
        check(states.rang(), "finishing old call must not clear new ringing state");
        states.offHook();
        check(e.incoming, "new incoming call must not become outgoing");
    }

    public static void main(String[] args) {
        startupIdleDoesNotFinalizeOrCreateARecording();
        declinedIncomingDoesNotContaminateNextCall();
        transientIdleNeverCutsActiveCall();
        anotherCallCancelsPendingEnd();
        ringingDuringPendingConfirmationRemainsCallScoped();
        System.out.println("SIM call-state regression tests passed");
    }
}
