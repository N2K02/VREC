package com.vrec.app.shell;

/** Bounded recovery and evidence, independent of Android for deterministic tests. */
final class VoipMicHealth {
    static final String[] SOURCES = { "MIC", "VOICE_COMMUNICATION", "VOICE_RECOGNITION" };
    private int attempts;
    private long openedAt, lastData, lastNonzero, silencedAt = -1;
    private boolean blocked;
    private String state = "OFF", reason = "NONE";
    private String history = "";
    private int silencedChecks;

    synchronized int next(long now) {
        if (blocked || attempts == SOURCES.length) return -1;
        int index = attempts++;
        openedAt = lastData = lastNonzero = now;
        silencedAt = -1;
        state = "ON";
        return index;
    }

    synchronized void samples(short[] data, int count, long now) {
        if (count <= 0) return;
        lastData = now;
        for (int i = 0; i < Math.min(count, data.length); i++) {
            if (data[i] != 0) { lastNonzero = now; break; }
        }
    }

    synchronized void failed() { event("OFF", "OPEN_FAILED"); }
    synchronized void readerStuck() { blocked = true; event("OFF", "READER_STOP_TIMEOUT"); }
    synchronized void protectCall() { blocked = true; event("OFF", "CALL_MIC_PROTECTED"); }

    synchronized boolean inspect(long now, Boolean silenced, boolean readerAlive) {
        if (blocked || attempts == 0 || state.equals("OFF")) return false;
        if (Boolean.TRUE.equals(silenced)) {
            silencedChecks++;
            state = "SILENCED";
            if (silencedAt < 0) silencedAt = now;
            if (now - silencedAt >= 1000) return problem("SILENCED", "POLICY_SILENCED");
            return false;
        }
        silencedAt = -1;
        state = "ON";
        if (!readerAlive) return problem("OFF", "READ_FAILED");
        if (now - openedAt < 3000) return false;
        if (now - lastData >= 3000) return problem("NO_DATA", "NO_DATA");
        if (now - lastNonzero >= 3000) return problem("ZERO_SAMPLES", "ZERO_SAMPLES");
        return false;
    }

    private boolean problem(String status, String why) {
        // No retry storm: keep the final recorder available if policy later unsilences it.
        if (!reason.equals(why) || !state.equals(status)) event(status, why);
        else state = status;
        return attempts < SOURCES.length;
    }

    private void event(String status, String why) {
        state = status;
        reason = why;
        String item = (attempts == 0 ? "NONE" : SOURCES[attempts - 1]) + ":" + why;
        if (!history.endsWith(item)) history += (history.isEmpty() ? "" : ",") + item;
        // Bounded across long calls even when the last source changes state repeatedly.
        if (history.length() > 180) history = history.substring(history.length() - 180);
    }

    synchronized String summary() {
        return "mic=" + state + ";source=" + (attempts == 0 ? "NONE" : SOURCES[attempts - 1])
                + ";attempts=" + attempts + ";reason=" + reason + ";silencedChecks=" + silencedChecks
                + ";events=" + (history.isEmpty() ? "NONE" : history);
    }
}
