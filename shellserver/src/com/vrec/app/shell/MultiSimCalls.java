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
 * The default-subscription callback stays registered as its own source: it
 * duplicates one SIM's state, which the combination makes harmless, and it is
 * still there if per-subscription registration is refused.
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
        for (int s : states.values()) {
            if (s > next) next = s;
        }
        if (reported && next == combined) {
            return NO_CHANGE;
        }
        reported = true;
        combined = next;
        return next;
    }
}
