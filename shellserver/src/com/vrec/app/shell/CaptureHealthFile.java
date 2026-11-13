package com.vrec.app.shell;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Bounded metadata only; no recording names, app identities, or audio samples. */
final class CaptureHealthFile {
    private final File target;
    CaptureHealthFile(File target) { this.target = target; }

    synchronized void write(long atMs, boolean active, String mic, PcmChannelHealth.Snapshot pcm) throws IOException {
        String line = "VREC_LIVE_1 | at=" + atMs + " | active=" + (active ? 1 : 0)
                + " | " + mic + " | " + pcm.summary() + " | " + pcm.verdict() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1024) throw new IOException("diagnostic exceeds bound");
        File temporary = new File(target.getPath() + ".tmp");
        try {
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
