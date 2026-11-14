package com.vrec.app.shell;

public final class VoipMicHealthTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) {
        VoipMicHealth h = new VoipMicHealth();
        check(h.next(0) == 0, "MIC first");
        h.samples(new short[] { 0, 0 }, 2, 2500);
        check(!h.inspect(2999, null, true), "startup grace");
        check(!h.inspect(3000, null, true), "zero PCM with active frames cannot trigger a mic restart");
        check(h.summary().contains("ZERO_SAMPLES"), "silent PCM is visible in diagnostics");
        check(!h.inspect(4000, null, true), "continued silence cannot exhaust recovery sources");
        check(h.next(4000) == 1, "next source remains available for confirmed capture failures");
        h.samples(new short[] { 1 }, 1, 5999);
        check(!h.inspect(6000, false, true), "even very quiet nonzero audio prevents zero detection");
        check(!h.inspect(6000, true, true), "silence confirmation grace");
        check(!h.inspect(6500, false, true), "transient policy silence clears");
        check(!h.inspect(7000, true, true), "new silence interval");
        check(h.inspect(8000, true, true), "confirmed own-policy silence triggers fallback");
        check(h.next(8000) == 2, "recognition fallback");
        check(!h.inspect(11000, null, true), "final source cannot cause endless reopen");
        check(h.summary().contains("mic=NO_DATA"), "missing data is separate evidence");
        check(h.next(12000) == -1, "three opens maximum per call");
        h.samples(new short[] { 300 }, 1, 12001);
        check(!h.inspect(12002, false, true), "last recorder can recover without reopen");
        check(h.summary().contains("mic=ON"), "recovered state");
        h.protectCall();
        check(h.next(20000) == -1 && !h.inspect(20000, true, false), "call protection terminal");
        check(h.summary().contains("CALL_MIC_PROTECTED"), "guard persisted");
        VoipMicHealth fail = new VoipMicHealth();
        for (int i = 0; i < 3; i++) { check(fail.next(i) == i, "fallback ordering"); fail.failed(); }
        check(fail.next(4) == -1 && fail.summary().contains("mic=OFF"), "open failures exhausted");
        VoipMicHealth read = new VoipMicHealth();
        read.next(0);
        check(read.inspect(1, null, false), "dead reader recovers immediately");
        read.readerStuck();
        check(read.next(2) == -1, "never overlap an unreleased recorder");
        check(read.summary().contains("READER_STOP_TIMEOUT"), "timeout reason retained");
        VoipMicHealth fresh = new VoipMicHealth();
        check(fresh.next(0) == 0, "new call resets recovery budget");
        for (int i = 0; i < 100000; i++) fresh.inspect(i * 500L, true, true);
        check(fresh.summary().length() < 400, "diagnostic remains bounded");
        System.out.println("VoIP microphone recovery regression tests passed");
    }
}
