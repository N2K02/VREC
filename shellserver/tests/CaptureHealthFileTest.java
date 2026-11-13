package com.vrec.app.shell;
import java.io.File;
import java.nio.file.Files;

public final class CaptureHealthFileTest {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("vrec-live-health").toFile();
        File file = new File(dir, "health");
        try {
            CaptureHealthFile writer = new CaptureHealthFile(file);
            PcmChannelHealth pcm = new PcmChannelHealth();
            pcm.accept(new short[] { 1000, 0, -1000, 0 }, 2);
            writer.write(1000, true, "mic=SILENCED;source=MIC;attempts=1", pcm.snapshot());
            String live = Files.readString(file.toPath());
            check(live.startsWith("VREC_LIVE_1 | at=1000 | active=1 | mic=SILENCED"), "live state");
            check(live.contains("RIGHT_CHANNEL_QUIET"), "actual encoder metrics attached");
            writer.write(2000, false, "mic=OFF;source=MIC", pcm.snapshot());
            String stopped = Files.readString(file.toPath());
            check(stopped.contains("active=0") && !stopped.contains("at=1000"), "atomic replacement marks end");
            try {
                writer.write(3000, true, "x".repeat(1100), pcm.snapshot());
                throw new AssertionError("oversize accepted");
            } catch (java.io.IOException expected) { }
            check(Files.readString(file.toPath()).equals(stopped), "failed write preserves previous snapshot");
            check(!new File(file.getPath() + ".tmp").exists(), "no leftover temporary");
        } finally { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(dir.toPath()); }
        System.out.println("Live capture health regression tests passed");
    }
}
