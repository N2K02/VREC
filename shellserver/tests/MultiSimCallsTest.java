package com.vrec.app.shell;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Dual-SIM call-state combination and watch readiness; pure Java, no device. */
public final class MultiSimCallsTest {
    private static void check(boolean c, String m) { if (!c) throw new AssertionError(m); }

    private static final int D = MultiSimCalls.DEFAULT_SOURCE;
    private static final int S1 = 1, S2 = 2, S3 = 3;
    private static final int IDLE = MultiSimCalls.IDLE, RING = MultiSimCalls.RINGING,
            OFF = MultiSimCalls.OFFHOOK, NONE = MultiSimCalls.NO_CHANGE;

    private static Set<Integer> sims(Integer... ids) { return new HashSet<>(Arrays.asList(ids)); }

    /** Two SIMs, both watches ready, all idle. */
    private static MultiSimCalls dual() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE);
        m.setActiveSims(sims(S1, S2));
        m.update(S1, IDLE);
        m.update(S2, IDLE);
        check(!m.defaultTrusted(), "both watches ready: default retired");
        return m;
    }

    static void firstReportPassesThrough() {
        MultiSimCalls m = new MultiSimCalls();
        check(m.update(D, IDLE) == IDLE, "registration-time idle must still reach CallStates");
        m.setActiveSims(sims(S1));
        check(m.update(S1, IDLE) == NONE, "further idles are no news");
    }

    static void registeredIsNotReady() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE);
        m.setActiveSims(sims(S1, S2));
        check(m.defaultTrusted(), "watches registered but silent: default still counts");
        check(m.update(D, OFF) == OFF, "a call heard only by the default watch is recorded");
        m.update(S1, IDLE);
        check(m.defaultTrusted() && m.combined() == OFF, "one of two ready: default still counts, call stays up");
        m.update(S2, OFF);
        check(!m.defaultTrusted() && m.combined() == OFF, "all ready: the SIM's own watch carries the call");
        check(m.update(S2, IDLE) == IDLE, "and its own idle ends it");
    }

    static void watchThatNeverReportsKeepsDefault() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE);
        m.setActiveSims(sims(S1, S2));   // S2's registration failed: it never reports
        m.update(S1, IDLE);
        check(m.update(D, OFF) == OFF && m.defaultTrusted(), "default covers the unwatched SIM");
        check(m.update(D, IDLE) == IDLE, "and its end");
    }

    static void incomingOnSecondSim() {
        MultiSimCalls m = dual();
        check(m.update(S2, RING) == RING, "SIM2 ringing must be heard");
        check(m.update(S2, OFF) == OFF, "SIM2 answered must start the call");
        check(m.update(S2, IDLE) == IDLE, "SIM2 hung up must end it");
    }

    static void staleDuplicateCannotHoldCallOpen() {
        MultiSimCalls m = dual();
        check(m.update(S1, OFF) == OFF, "SIM1 call starts from its own watch");
        check(m.update(S1, IDLE) == IDLE, "SIM1's own idle ends it");
        check(m.update(D, OFF) == NONE, "a late OFFHOOK from the default duplicate is ignored");
        check(m.update(D, IDLE) == NONE, "and so is its late IDLE");
        check(m.combined() == IDLE, "call stays over");
    }

    static void callWaitingAcrossSims() {
        MultiSimCalls m = dual();
        m.update(S1, OFF);
        check(m.update(S2, RING) == NONE, "a call waiting on SIM2 must not disturb SIM1's call");
        check(m.update(S2, OFF) == NONE, "answering it keeps one ongoing call");
        check(m.update(S1, IDLE) == NONE, "SIM1 ending while SIM2 is up is not an end");
        check(m.update(S2, IDLE) == IDLE, "the last call ending is");
    }

    static void firstCallEndsWhileSecondRings() {
        MultiSimCalls m = dual();
        m.update(S1, OFF); m.update(S2, RING);
        check(m.update(S1, IDLE) == RING, "same as single-SIM telephony: off-hook gives way to ringing");
    }

    static void simAddedDuringCall() {
        MultiSimCalls m = dual();
        m.update(D, OFF);
        m.update(S1, OFF);
        check(m.setActiveSims(sims(S1, S2, S3)) == NONE, "a SIM arriving mid-call changes nothing");
        check(m.defaultTrusted(), "new SIM not ready: default counts again");
        check(m.update(S3, IDLE) == NONE && !m.defaultTrusted(), "new SIM ready: default retired, call untouched");
        check(m.combined() == OFF, "call still up");
        check(m.update(S1, IDLE) == IDLE, "SIM1's own idle ends it; the retired default's stale OFFHOOK does not hold it");
    }

    static void otherSimRemovedDuringCall() {
        MultiSimCalls m = dual();
        m.update(S1, OFF);
        check(m.setActiveSims(sims(S1)) == NONE, "removing the idle SIM does not touch the call");
        check(m.combined() == OFF, "call still up");
        check(m.update(S2, OFF) == NONE, "a late callback from the removed SIM is ignored");
        check(m.update(S1, IDLE) == IDLE, "the call ends normally");
    }

    static void callsSimRemovedDuringCall() {
        MultiSimCalls m = dual();
        m.update(S2, OFF);
        check(m.setActiveSims(sims(S1)) == IDLE,
                "the call's SIM leaving is an IDLE claim (CallStates still confirms it)");
        check(!m.has(S2), "its state is forgotten");
    }

    static void delayedPerSimCallbackStillEnds() {
        MultiSimCalls m = dual();
        m.update(S1, RING);
        check(m.update(S1, OFF) == OFF, "answered");
        check(m.update(S1, OFF) == NONE, "repeated OFFHOOK is not a new call");
        check(m.update(S1, IDLE) == IDLE, "the end arrives even after a repeated callback");
    }

    static void noSimsMeansDefaultOnly() {
        MultiSimCalls m = new MultiSimCalls();
        m.update(D, IDLE);
        m.setActiveSims(sims());
        check(m.defaultTrusted(), "no SIMs listed: default counts");
        check(m.update(D, OFF) == OFF, "and is heard");
    }

    static void unknownStatesIgnored() {
        MultiSimCalls m = new MultiSimCalls();
        check(m.update(D, 7) == NONE && m.sources() == 0, "unknown state ignored");
    }

    public static void main(String[] args) {
        firstReportPassesThrough();
        registeredIsNotReady();
        watchThatNeverReportsKeepsDefault();
        incomingOnSecondSim();
        staleDuplicateCannotHoldCallOpen();
        callWaitingAcrossSims();
        firstCallEndsWhileSecondRings();
        simAddedDuringCall();
        otherSimRemovedDuringCall();
        callsSimRemovedDuringCall();
        delayedPerSimCallbackStillEnds();
        noSimsMeansDefaultOnly();
        unknownStatesIgnored();
        System.out.println("MultiSimCallsTest: OK");
    }
}
