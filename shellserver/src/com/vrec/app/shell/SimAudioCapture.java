package com.vrec.app.shell;

import android.media.MediaCodec;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.audio.AudioDirectCapture;
import com.genymobile.scrcpy.audio.AudioSource;
import com.genymobile.scrcpy.util.Ln;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Independent VRec implementation of selectable shell-privileged SIM capture.
 * Every output is 48 kHz PCM16 stereo, matching the existing Opus encoder.
 * Dual mode places uplink on L and downlink on R; a hardware-specific source
 * may still be silent or unavailable, so neither channel implies a participant.
 */
final class SimAudioCapture {
    static final int VOICE_CALL = 0;
    static final int UPLINK = 1;
    static final int DOWNLINK = 2;
    static final int DUAL = 3;

    private SimAudioCapture() {}

    static boolean valid(int mode) { return mode >= VOICE_CALL && mode <= DUAL; }

    static AudioCapture create(int mode) {
        switch (mode) {
            case UPLINK: return new AudioDirectCapture(AudioSource.VOICE_CALL_UPLINK);
            case DOWNLINK: return new AudioDirectCapture(AudioSource.VOICE_CALL_DOWNLINK);
            case DUAL: return new DualCapture();
            default: return new AudioDirectCapture(AudioSource.VOICE_CALL);
        }
    }

    static String label(int mode) {
        switch (mode) {
            case UPLINK: return "VOICE_UPLINK";
            case DOWNLINK: return "VOICE_DOWNLINK";
            case DUAL: return "UPLINK_DOWNLINK";
            default: return "VOICE_CALL";
        }
    }

    /**
     * The existing AudioDirectCapture emits stereo PCM even for a mono call
     * source. Average its channels into one sample per source, then interleave
     * independent uplink/downlink readings. Never add/mix unrelated samples.
     */
    private static final class DualCapture implements AudioCapture {
        private final AudioCapture near = new AudioDirectCapture(AudioSource.VOICE_CALL_UPLINK);
        private final AudioCapture far = new AudioDirectCapture(AudioSource.VOICE_CALL_DOWNLINK);
        private final ByteBuffer nearBuffer = ByteBuffer.allocateDirect(AudioConfig.MAX_READ_SIZE)
                .order(ByteOrder.nativeOrder());
        private final ByteBuffer farBuffer = ByteBuffer.allocateDirect(AudioConfig.MAX_READ_SIZE)
                .order(ByteOrder.nativeOrder());
        private final MediaCodec.BufferInfo nearInfo = new MediaCodec.BufferInfo();
        private final MediaCodec.BufferInfo farInfo = new MediaCodec.BufferInfo();
        private boolean started;

        @Override public void checkCompatibility() throws AudioCaptureException {
            near.checkCompatibility();
            far.checkCompatibility();
        }

        @Override public void start() throws AudioCaptureException {
            near.start();
            try {
                far.start();
                started = true;
            } catch (Throwable t) {
                near.stop();
                Ln.w("Dual SIM source unsupported by device; capture startup aborted");
                throw new AudioCaptureException();
            }
        }

        @Override public int read(ByteBuffer out, MediaCodec.BufferInfo info) {
            if (!started) return -1;
            nearBuffer.clear();
            farBuffer.clear();
            int a = near.read(nearBuffer, nearInfo);
            if (a < 0) return a;
            int b = far.read(farBuffer, farInfo);
            if (b < 0) return b;
            if (a == 0 || b == 0) return 0;
            int frames = Math.min(Math.min(a, b) / 4, out.remaining() / 4);
            nearBuffer.order(ByteOrder.nativeOrder());
            farBuffer.order(ByteOrder.nativeOrder());
            out.order(ByteOrder.nativeOrder());
            for (int i = 0; i < frames; i++) {
                int l = (nearBuffer.getShort(i * 4) + nearBuffer.getShort(i * 4 + 2)) / 2;
                int r = (farBuffer.getShort(i * 4) + farBuffer.getShort(i * 4 + 2)) / 2;
                out.putShort((short) l);
                out.putShort((short) r);
            }
            int bytes = frames * 4;
            info.set(0, bytes, nearInfo.presentationTimeUs, 0);
            return bytes;
        }

        @Override public void stop() {
            started = false;
            try { near.stop(); } finally { far.stop(); }
        }
    }
}
