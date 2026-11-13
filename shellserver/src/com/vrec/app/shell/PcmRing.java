package com.vrec.app.shell;

/**
 * Bounded PCM sample queue shared by one reader thread and the encoder.
 * Pure Java (no Android) so jitter/pacing behaviour is testable on a JVM.
 *
 * The microphone reader delivers in chunks larger than one encoder block
 * (40 ms reads vs 20 ms blocks), so a non-blocking take at a fixed clock
 * tick underruns on ordinary jitter and zero-fills the user's voice.
 * [takeWithin] waits a bounded time for a full block instead, so a healthy
 * microphone is never chopped, and a stalled one never blocks the encoder
 * (and with it the other person's channel) longer than the given timeout.
 */
final class PcmRing {
    interface Clock {
        long nowUs();
        void sleepUs(long durationUs) throws InterruptedException;
    }

    private final short[] ring;
    private int head;
    private int size;
    private boolean closed;

    PcmRing(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        ring = new short[capacity];
    }

    synchronized void put(short[] src, int n) {
        int count = Math.min(n, src.length);
        for (int i = 0; i < count; i++) {
            if (size == ring.length) {
                // Full: the oldest goes, nothing blocks the reader.
                head = (head + 1) % ring.length;
                size--;
            }
            ring[(head + size) % ring.length] = src[i];
            size++;
        }
        notifyAll();
    }

    /** No more samples will come: wake any waiting take. */
    synchronized void close() {
        closed = true;
        notifyAll();
    }

    synchronized int size() {
        return size;
    }

    /**
     * Fill dst with n samples. Waiting for them if [wait] (until closed);
     * otherwise what there is, and silence for the rest. False only if it
     * was waiting and the source closed short.
     */
    synchronized boolean take(short[] dst, int n, boolean wait) throws InterruptedException {
        if (wait) {
            while (size < n && !closed) {
                wait(100);
            }
            if (size < n) return false;
        }
        copyOut(dst, n);
        return true;
    }

    /**
     * Wait at most [timeoutMs] for n samples, then copy what is there and
     * zero the rest. Returns the number of real samples copied (n when the
     * block was complete). A closed source returns immediately.
     */
    synchronized int takeWithin(short[] dst, int n, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (size < n && !closed) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) break;
            wait(remainingMs);
        }
        return copyOut(dst, n);
    }

    /**
     * A full microphone block supplies its own timing. A short/closed source
     * must fall back to the output deadline before emitting inserted silence.
     * Sleep outside the queue monitor so the producer and close remain free.
     */
    int takePaced(short[] dst, int n, long timeoutMs, long dueUs, Clock clock) throws InterruptedException {
        int got = takeWithin(dst, n, timeoutMs);
        if (got < n) paceUntil(dueUs, clock);
        return got;
    }

    static void paceUntil(long dueUs, Clock clock) throws InterruptedException {
        long remaining;
        while ((remaining = dueUs - clock.nowUs()) > 0) clock.sleepUs(remaining);
    }

    /** Keep at most [max] samples waiting: the rest is the past. */
    synchronized void dropBeyond(int max) {
        if (size > max) {
            int drop = size - max;
            head = (head + drop) % ring.length;
            size = max;
        }
    }

    private int copyOut(short[] dst, int n) {
        int got = Math.min(n, size);
        for (int i = 0; i < got; i++) {
            dst[i] = ring[head];
            head = (head + 1) % ring.length;
        }
        size -= got;
        for (int i = got; i < n; i++) {
            dst[i] = 0;
        }
        return got;
    }
}
