package com.vrec.app.shell;

/** Dual-SIM call-state combination; pure Java, no device. */
public final class MultiSimCallsTest {
    private static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    private static final int D = MultiSimCalls.DEFAULT_SOURCE;
    private static final int S1 = 1, S2 = 2;
    private static final int IDLE = MultiSimCalls.IDLE, RING = MultiSimCalls.RINGING,
            OFF = MultiSimCalls.OFFHOOK, NONE = MultiSimCalls.NO_CHANGE;

    static void firstReportPassesThrough() {
        MultiSimCalls m = new MultiSimCalls();
        check(m.update(D, IDLE) == IDLE, "registration-time idle must still reach CallStates");
        check(m.update(S1, IDLE) == NONE && m.update(S2, IDLE) == NONE, "further idles are no news");
    }

    static void incomingOnSecondSim() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE); m.update(S1, IDLE); m.update(S2, IDLE);
        check(m.update(S2, RING) == RING, "SIM2 ringing must be heard");
        check(m.update(S2, OFF) == OFF, "SIM2 answered must start the call");
        check(m.update(S2, IDLE) == IDLE, "SIM2 hung up must end it");
    }

    static void outgoingOnDefaultSimReportedTwice() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE); m.update(S1, IDLE);
        check(m.update(D, OFF) == OFF, "default off-hook starts");
        check(m.update(S1, OFF) == NONE, "the same call from its own SIM watch is not a second start");
        check(m.update(D, IDLE) == NONE, "one source idle while the other is off-hook: no end");
        check(m.update(S1, IDLE) == IDLE, "both idle: end");
    }

    static void callWaitingAcrossSims() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(S1, IDLE); m.update(S2, IDLE);
        m.update(S1, OFF);
        check(m.update(S2, RING) == NONE, "a call waiting on SIM2 must not disturb SIM1's call");
        check(m.update(S2, OFF) == NONE, "answering it keeps one ongoing call");
        check(m.update(S1, IDLE) == NONE, "SIM1 ending while SIM2 is up is not an end");
        check(m.update(S2, IDLE) == IDLE, "the last call ending is");
    }

    static void firstCallEndsWhileSecondRings() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(S1, IDLE); m.update(S2, IDLE);
        m.update(S1, OFF); m.update(S2, RING);
        check(m.update(S1, IDLE) == RING, "same as single-SIM telephony: off-hook gives way to ringing");
    }

    static void simRemovedMidCall() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE); m.update(S2, OFF);
        check(m.combined() == OFF, "SIM2 call up");
        check(m.remove(S2) == IDLE, "a removed SIM no longer holds a call open");
        check(m.remove(S2) == NONE, "removing twice is harmless");
        check(!m.has(S2) && m.sources() == 1, "only the default source remains");
    }

    static void unknownStatesIgnored() {
        MultiSimCalls m = new MultiSimCalls();
        check(m.update(S1, 7) == NONE && m.sources() == 0, "unknown state ignored");
    }

    public static void main(String[] args) {
        firstReportPassesThrough();
        incomingOnSecondSim();
        outgoingOnDefaultSimReportedTwice();
        callWaitingAcrossSims();
        firstCallEndsWhileSecondRings();
        simRemovedMidCall();
        unknownStatesIgnored();
        System.out.println("MultiSimCallsTest: OK");
    }
}
