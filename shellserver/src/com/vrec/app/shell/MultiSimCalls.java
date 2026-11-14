package com.vrec.app.shell;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * it only COUNTS while some active SIM's watch is not READY - registered and
 * having delivered its first state. Two callbacks reporting the same SIM can
 * arrive in either order, and a late OFFHOOK from the duplicate would hold
 * the call open after its own SIM said IDLE; once every SIM's watch is ready,
 * the duplicate is ignored. Registration alone is not readiness: a watch
 * registered during a SIM change may not have reported yet, and until it
 * does the default watch still covers that SIM. A watch that could not be
 * registered never becomes ready, so the default watch keeps counting.
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
    /** The active SIMs' subscription ids, as last read. */
    private final Set<Integer> active = new HashSet<>();
    private int combined = IDLE;
    /** Whether anything has been reported yet: the first report is passed on
     *  as-is, so CallStates still sees the registration-time state. */
    private boolean reported;

    /** [source] now reports [state]; returns the combined state if it changed. */
    synchronized int update(int source, int state) {
        if (state != IDLE && state != RINGING && state != OFFHOOK) {
            return NO_CHANGE;
        }
        if (source != DEFAULT_SOURCE && !active.contains(source)) {
            // A late callback from a SIM no longer active: it cannot hold a
            // call open.
            return NO_CHANGE;
        }
        states.put(source, state);
        return publish();
    }

    /**
     * The active SIMs now. SIMs that left stop counting (their state is
     * forgotten); SIMs that arrived count once their watch reports. Returns
     * the combined state if that changed it.
     */
    synchronized int setActiveSims(Set<Integer> now) {
        active.clear();
        active.addAll(now);
        states.keySet().removeIf(id -> id != DEFAULT_SOURCE && !active.contains(id));
        return reported ? publish() : NO_CHANGE;
    }

    /** Whether [subId]'s watch has delivered a state. */
    synchronized boolean ready(int subId) {
        return states.containsKey(subId);
    }

    /** Whether the default-subscription source counts: until every active
     *  SIM's own watch is ready. */
    synchronized boolean defaultTrusted() {
        if (active.isEmpty()) return true;
        for (int id : active) {
            if (!states.containsKey(id)) return true;
        }
        return false;
    }

    synchronized int combined() {
        return combined;
    }

    /** Whether [source] is being listened to and has reported. */
    synchronized boolean has(int source) {
        return states.containsKey(source);
    }

    synchronized int sources() {
        return states.size();
    }

    private int publish() {
        boolean useDefault = defaultTrusted();
        int next = IDLE;
        for (Map.Entry<Integer, Integer> e : states.entrySet()) {
            if (!useDefault && e.getKey() == DEFAULT_SOURCE) continue;
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
