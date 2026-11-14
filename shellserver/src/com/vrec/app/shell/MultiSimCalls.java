package com.vrec.app.shell;

import java.util.HashMap;
import java.util.Map;

/**
 * ONE PHONE-CALL STATE FROM EVERY SIM.
 *
 * A TelephonyCallback registered on the default TelephonyManager hears the
 * default subscription only, so on a dual-SIM phone a call on the other SIM
 * never reached CallStates and was never recorded. Each active subscription
 * now gets its own callback, and their states are combined here into the one
 * state a single-SIM phone reports: OFFHOOK while any line is off-hook, else
 * RINGING while any line rings, else IDLE.
 *
 * That is exactly the precedence telephony already applies to a single line
 * (call waiting on an active call stays OFFHOOK), so CallStates sees the same
 * edges it always has and none of its measured workarounds change.
 *
 * The default-subscription callback stays registered as its own source, but
 * it only COUNTS while some active SIM has no watch of its own (or none could
 * be registered). Two callbacks reporting the same SIM can arrive in either
 * order, and a late OFFHOOK from the duplicate would hold the call open after
 * its own SIM said IDLE; once every SIM is watched, the duplicate is ignored.
 *
 * Plain Java, so it can be tested without a device (MultiSimCallsTest).
 */
final class MultiSimCalls {

    static final int IDLE = 0;
    static final int RINGING = 1;
    static final int OFFHOOK = 2;
    /** Nothing new to tell CallStates. */
    static final int NO_CHANGE = -1;

    /** The default-subscription callback's source id. */
    static final int DEFAULT_SOURCE = Integer.MIN_VALUE;

    private final Map<Integer, Integer> states = new HashMap<>();
    private int combined = IDLE;
    /** Whether anything has been reported yet: the first report is passed on
     *  as-is, so CallStates still sees the registration-time state. */
    private boolean reported;
    /** Whether the default-subscription source counts. */
    private boolean defaultTrusted = true;

    /** [source] now reports [state]; returns the combined state if it changed. */
    synchronized int update(int source, int state) {
        if (state != IDLE && state != RINGING && state != OFFHOOK) {
            return NO_CHANGE;
        }
        states.put(source, state);
        return publish();
    }

    /** A SIM went away; whatever it said no longer counts. */
    synchronized int remove(int source) {
        if (states.remove(source) == null) {
            return NO_CHANGE;
        }
        return publish();
    }

    /** Whether the default-subscription source counts; returns the
     *  combined state if that changed it. */
    synchronized int setDefaultTrusted(boolean trusted) {
        if (defaultTrusted == trusted) {
            return NO_CHANGE;
        }
        defaultTrusted = trusted;
        return reported ? publish() : NO_CHANGE;
    }

    synchronized boolean defaultTrusted() {
        return defaultTrusted;
    }

    synchronized int combined() {
        return combined;
    }

    /** Whether [source] is being listened to. */
    synchronized boolean has(int source) {
        return states.containsKey(source);
    }

    synchronized int sources() {
        return states.size();
    }

    private int publish() {
        int next = IDLE;
        for (Map.Entry<Integer, Integer> e : states.entrySet()) {
            if (!defaultTrusted && e.getKey() == DEFAULT_SOURCE) continue;
            if (e.getValue() > next) next = e.getValue();
        }
        if (reported && next == combined) {
            return NO_CHANGE;
        }
        reported = true;
        combined = next;
        return next;
    }
}
