package com.vrec.app.shell;

/**
 * Cheap, metadata-only PCM16 stereo diagnostics. The left/right channels are
 * sound channels, NOT evidence that each call participant was captured.
 *
 * This class never stores raw audio or caller identity and is independent of
 * Android so its edge cases can be tested on a workstation.
 */
final class PcmChannelHealth {
    private long frames;
    private double leftSquares;
    private double rightSquares;
    private long clippedLeft;
    private long clippedRight;
    private long silentLeft;
    private long silentRight;

    synchronized void accept(short[] pcm, int availableFrames) {
        if (pcm == null || availableFrames <= 0) return;
        int count = Math.min(availableFrames, pcm.length / 2);
        for (int i = 0; i < count; i++) {
            int l = pcm[i * 2];
            int r = pcm[i * 2 + 1];
            leftSquares += (double) l * l;
            rightSquares += (double) r * r;
            if (l >= 32760 || l <= -32760) clippedLeft++;
            if (r >= 32760 || r <= -32760) clippedRight++;
            if (Math.abs(l) < 80) silentLeft++;
            if (Math.abs(r) < 80) silentRight++;
        }
        frames += count;
    }

    synchronized Snapshot snapshot() {
        if (frames == 0) return new Snapshot(0, 0, 0, 0, 0, 0, 0);
        return new Snapshot(frames, Math.sqrt(leftSquares / frames),
                Math.sqrt(rightSquares / frames),
                (double) silentLeft / frames, (double) silentRight / frames,
                (double) clippedLeft / frames, (double) clippedRight / frames);
    }

    static final class Snapshot {
        final long frames;
        final double leftRms, rightRms;
        final double leftSilentRatio, rightSilentRatio;
        final double leftClipRatio, rightClipRatio;

        Snapshot(long frames, double leftRms, double rightRms,
                double leftSilentRatio, double rightSilentRatio,
                double leftClipRatio, double rightClipRatio) {
            this.frames = frames;
            this.leftRms = leftRms;
            this.rightRms = rightRms;
            this.leftSilentRatio = leftSilentRatio;
            this.rightSilentRatio = rightSilentRatio;
            this.leftClipRatio = leftClipRatio;
            this.rightClipRatio = rightClipRatio;
        }

        // Human-readable metadata, never raw audio. Avoid calling a channel
        // "the remote party": device routing and HAL vary considerably.
        String summary() {
            if (frames == 0) return "PCM diagnostic: no frames captured";
            return String.format(java.util.Locale.US,
                    "PCM diagnostic: frames=%d L rms=%.1f silence=%.1f%% clip=%.2f%%; R rms=%.1f silence=%.1f%% clip=%.2f%%",
                    frames, leftRms, 100.0 * leftSilentRatio, 100.0 * leftClipRatio,
                    rightRms, 100.0 * rightSilentRatio, 100.0 * rightClipRatio);
        }
    }
}
