package com.vrec.app.shell;

public final class PcmRingTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static short[] ramp(int n, int from) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (from + i);
        return s;
    }

    public static void main(String[] args) throws Exception {
        short[] block = new short[960];

        // A 40 ms microphone read feeds two 20 ms blocks with no zero gap.
        PcmRing mic = new PcmRing(96000);
        mic.put(ramp(1920, 1), 1920);
        check(mic.takeWithin(block, 960, 0) == 960 && block[0] == 1 && block[959] == 960, "first block intact");
        check(mic.takeWithin(block, 960, 0) == 960 && block[0] == 961, "second block intact");

        // Jitter: the block arrives after the read began - wait for it, no zero fill.
        PcmRing late = new PcmRing(96000);
        Thread producer = new Thread(() -> {
            try { Thread.sleep(25); } catch (InterruptedException ignored) { }
            late.put(ramp(1920, 100), 1920);
        });
        producer.start();
        long t0 = System.nanoTime();
        int got = late.takeWithin(block, 960, 200);
        long waitedMs = (System.nanoTime() - t0) / 1_000_000L;
        producer.join();
        check(got == 960 && block[0] == 100 && block[959] != 0, "late mic block waited for, not zero-filled");
        check(waitedMs < 150, "returns as soon as the block is complete");

        // Stalled microphone: bounded wait, then silence - the encoder keeps going.
        PcmRing stalled = new PcmRing(96000);
        stalled.put(ramp(100, 1), 100);
        t0 = System.nanoTime();
        got = stalled.takeWithin(block, 960, 30);
        waitedMs = (System.nanoTime() - t0) / 1_000_000L;
        check(got == 100 && block[99] == 100 && block[100] == 0 && block[959] == 0, "partial then silence");
        check(waitedMs >= 25 && waitedMs < 500, "wait is bounded by the timeout");

        // Zero/negative timeout never blocks.
        t0 = System.nanoTime();
        check(new PcmRing(10).takeWithin(block, 960, -5) == 0, "empty, no wait");
        check((System.nanoTime() - t0) / 1_000_000L < 20, "negative timeout is immediate");

        // Closing wakes a waiting take at once (reader died or mic recovery).
        PcmRing closing = new PcmRing(96000);
        Thread closer = new Thread(() -> {
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
            closing.close();
        });
        closer.start();
        t0 = System.nanoTime();
        check(closing.takeWithin(block, 960, 5000) == 0, "closed source returns nothing");
        check((System.nanoTime() - t0) / 1_000_000L < 1000, "close wakes the waiter");
        closer.join();
        check(!closing.take(block, 960, true), "blocking take on closed short source reports false");

        // Bounded backlog keeps the two sides aligned after a stall.
        PcmRing backlog = new PcmRing(96000);
        backlog.put(ramp(5000, 1), 5000);
        backlog.dropBeyond(9600);
        check(backlog.size() == 5000, "under the cap nothing is dropped");
        backlog.dropBeyond(960);
        check(backlog.size() == 960, "oldest dropped beyond the cap");
        backlog.take(block, 960, false);
        check(block[0] == 4041 && block[959] == 5000, "newest samples kept");

        // Overflow drops the oldest; the writer never blocks.
        PcmRing small = new PcmRing(4);
        small.put(new short[] { 1, 2, 3, 4, 5, 6 }, 6);
        short[] four = new short[4];
        small.take(four, 4, false);
        check(four[0] == 3 && four[3] == 6 && small.size() == 0, "overflow keeps the newest");

        // Non-blocking take keeps its legacy semantics for the playback side.
        PcmRing far = new PcmRing(100);
        far.put(new short[] { 7, 8 }, 2);
        short[] out = new short[4];
        check(far.take(out, 4, false) && out[0] == 7 && out[1] == 8 && out[2] == 0, "partial then zeros");

        System.out.println("PCM ring pacing regression tests passed");
    }
}
