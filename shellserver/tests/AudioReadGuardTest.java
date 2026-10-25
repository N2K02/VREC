package com.genymobile.scrcpy.audio;

public final class AudioReadGuardTest {
    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        check(!AudioReadGuard.retryEmptyRead(0), "positive count required");
        check(AudioReadGuard.retryEmptyRead(1), "transient zero is retryable");
        check(AudioReadGuard.retryEmptyRead(AudioReadGuard.MAX_ZERO_READS), "bounded final retry");
        check(!AudioReadGuard.retryEmptyRead(AudioReadGuard.MAX_ZERO_READS + 1), "no indefinite loop");
        check(!AudioReadGuard.retryEmptyRead(-1), "negative capture errors never retried");
        System.out.println("Audio read guard regression tests passed");
    }
}
