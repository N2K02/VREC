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
        /** When set, what the audio says, overriding [audioInCall]. */
        Integer audio;
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
        @Override public int phoneCallAudio() {
            if (audio != null) return audio;
            return audioInCall ? CallStates.AUDIO_UP : CallStates.AUDIO_DOWN;
        }
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

    private static void unreadableAudioModeIsNotAnEnd() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.offHook();
        states.idle();
        e.audio = CallStates.AUDIO_UNKNOWN;
        for (int i = 1; i < CallStates.MAX_UNKNOWN_CHECKS; i++) {
            states.endCheck();
            check(states.inCall() && e.ended == 0, "failed mode read " + i + " must not end the call");
        }
        check(e.continued == 0, "unknown is not reported as the call going on");
        states.endCheck();
        check(!states.inCall() && e.ended == 1, "bounded: a mode that never reads ends the call");
    }

    private static void oneGoodReadResetsUnknownCount() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.offHook();
        states.idle();
        e.audio = CallStates.AUDIO_UNKNOWN;
        states.endCheck();
        states.endCheck();
        e.audio = CallStates.AUDIO_UP;
        states.endCheck();
        e.audio = CallStates.AUDIO_UNKNOWN;
        states.endCheck();
        states.endCheck();
        check(states.inCall() && e.ended == 0, "failures separated by a good read do not add up");
    }

    private static void messengerHandOverNeedsConsecutiveChecks() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.offHook();
        states.idle();
        e.audio = CallStates.AUDIO_MESSENGER;
        states.endCheck();
        check(states.inCall(), "one messenger-held check must not end the phone call");
        e.audio = CallStates.AUDIO_UP;
        states.endCheck();
        e.audio = CallStates.AUDIO_MESSENGER;
        states.endCheck();
        check(states.inCall(), "an in-call read in between resets the count");
        states.endCheck();
        check(!states.inCall() && e.ended == 1, "consecutive messenger checks hand over");
    }

    private static void newIdleResetsCounters() {
        Effects e = new Effects();
        CallStates states = new CallStates(e);
        states.offHook();
        states.idle();
        e.audio = CallStates.AUDIO_UNKNOWN;
        states.endCheck();
        states.endCheck();
        states.offHook();
        states.idle();
        states.endCheck();
        check(states.inCall(), "a new call's checks start from zero");
    }

    private static void audioDecision() {
        int ic = CallStates.MODE_IN_CALL, com = CallStates.MODE_IN_COMMUNICATION;
        check(CallStates.phoneCallAudio(null, false, null) == CallStates.AUDIO_UNKNOWN, "unreadable mode is unknown");
        check(CallStates.phoneCallAudio(ic, true, false) == CallStates.AUDIO_UP, "in-call is telecom's");
        check(CallStates.phoneCallAudio(com, false, null) == CallStates.AUDIO_UP,
                "communication with no messenger keeps the call (Wi-Fi calling)");
        check(CallStates.phoneCallAudio(com, true, null) == CallStates.AUDIO_UP,
                "telecom unknown keeps the phone recording");
        check(CallStates.phoneCallAudio(com, true, true) == CallStates.AUDIO_UP,
                "telecom still in a call keeps the phone recording");
        check(CallStates.phoneCallAudio(com, true, false) == CallStates.AUDIO_MESSENGER,
                "messenger in a call and telecom idle: hand over");
        check(CallStates.phoneCallAudio(0, false, null) == CallStates.AUDIO_DOWN, "normal mode: over");
        check(CallStates.phoneCallAudio(1, true, false) == CallStates.AUDIO_DOWN, "ringtone mode: over");
    }

    public static void main(String[] args) {
        unreadableAudioModeIsNotAnEnd();
        oneGoodReadResetsUnknownCount();
        messengerHandOverNeedsConsecutiveChecks();
        newIdleResetsCounters();
        audioDecision();
        startupIdleDoesNotFinalizeOrCreateARecording();
        declinedIncomingDoesNotContaminateNextCall();
        transientIdleNeverCutsActiveCall();
        anotherCallCancelsPendingEnd();
        ringingDuringPendingConfirmationRemainsCallScoped();
        System.out.println("SIM call-state regression tests passed");
    }
}
