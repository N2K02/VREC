package com.vrec.app.shell;

/** No Android device needed: stereo PCM frame accounting and silence metrics. */
public final class PcmChannelHealthTest {
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        PcmChannelHealth health = new PcmChannelHealth();
        check(health.snapshot().frames == 0, "empty capture");
        health.accept(null, 5);
        health.accept(new short[] { 1000, 0, -1000, 0, 32767, 100 }, 3);
        health.accept(new short[] { 1200, 0, -1200, 0 }, 9); // clamp to buffer
        PcmChannelHealth.Snapshot result = health.snapshot();
        check(result.frames == 5, "count interleaved frames, never individual samples");
        check(result.leftRms > result.rightRms, "distinct stereo channels");
        check(result.rightSilentRatio > result.leftSilentRatio, "detect quiet channel");
        check(result.leftClipRatio > 0 && result.rightClipRatio == 0, "clip ratios");
        check(result.summary().contains("PCM diagnostic: frames=5"), "summary available");
        health.accept(new short[] { 1 }, 1); // odd/truncated frame ignored
        check(health.snapshot().frames == 5, "never read beyond supplied sample pair");
        System.out.println("PCM channel diagnostics regression tests passed");
    }
}
