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
        check("RIGHT_CHANNEL_QUIET".equals(result.verdict()),
                "quiet channel diagnostic must not infer speaker identity");
        PcmChannelHealth quiet = new PcmChannelHealth();
        quiet.accept(new short[] { 0, 0, 0, 0 }, 2);
        check("BOTH_CHANNELS_QUIET".equals(quiet.snapshot().verdict()), "silent PCM detection");
        PcmChannelHealth loud = new PcmChannelHealth();
        loud.accept(new short[] { 2000, 2000, -2000, -2000 }, 2);
        check("AUDIO_DETECTED".equals(loud.snapshot().verdict()), "active PCM detection");
        health.accept(new short[] { 1 }, 1); // odd/truncated frame ignored
        check(health.snapshot().frames == 5, "never read beyond supplied sample pair");
        PcmChannelHealth empty = new PcmChannelHealth();
        empty.accept(new short[] { 10, 10 }, 0);
        empty.accept(new short[] { 10, 10 }, -5);
        check("NO_AUDIO".equals(empty.snapshot().verdict()), "invalid frame counts cannot fabricate audio");
        PcmChannelHealth leftQuiet = new PcmChannelHealth();
        leftQuiet.accept(new short[] { 0, 2000, 0, -2000 }, 2);
        check("LEFT_CHANNEL_QUIET".equals(leftQuiet.snapshot().verdict()),
                "local or remote channel silence is not mislabeled");
        PcmChannelHealth clipped = new PcmChannelHealth();
        clipped.accept(new short[] { 32767, 32767, -32768, -32768 }, 2);
        check("CLIPPING".equals(clipped.snapshot().verdict()), "clipping must be reported");
        PcmChannelHealth threshold = new PcmChannelHealth();
        threshold.accept(new short[] { 90, 90, -90, -90 }, 2);
        check("AUDIO_DETECTED".equals(threshold.snapshot().verdict()),
                "RMS threshold boundary must remain stable");
        PcmChannelHealth longCall = new PcmChannelHealth();
        for (int i = 0; i < 10000; i++) {
            longCall.accept(new short[] { 400, -400, -400, 400 }, 2);
        }
        check(longCall.snapshot().frames == 20000L, "long-running PCM counters must accumulate");
        check("AUDIO_DETECTED".equals(longCall.snapshot().verdict()),
                "long-running audio diagnostics must remain stable");
        System.out.println("PCM channel diagnostics regression tests passed");
    }
}
