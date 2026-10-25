package com.genymobile.scrcpy.audio;

/** Stateless, Android-independent decision for empty PCM input reads. */
public final class AudioReadGuard {
    private AudioReadGuard() {}
    public static final int MAX_ZERO_READS = 20;
    public static final int BACKOFF_MILLIS = 10;

    public static boolean retryEmptyRead(int consecutiveZeros) {
        return consecutiveZeros > 0 && consecutiveZeros <= MAX_ZERO_READS;
    }
}
