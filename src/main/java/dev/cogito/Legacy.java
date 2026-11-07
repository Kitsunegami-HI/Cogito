package dev.cogito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Up to 0.2.0 the mod was called AI Player and kept its folders under "aiplayer". They are moved, not copied, once. */
public final class Legacy {
    public static final String OLD_NAME = "aiplayer";

    private Legacy() {}

    /** Moves {@code old} to {@code now} when only the old one exists, so memory, goals and keys carry over. */
    public static void carryOver(Path old, Path now) {
        if (old == null || Files.exists(now) || !Files.isDirectory(old)) {
            return;
        }
        try {
            Files.move(old, now);
            CogitoMod.LOG.info("Moved {} to {}: this mod was called AI Player before", old, now);
        } catch (IOException e) {
            CogitoMod.LOG.warn("Could not move {} to {}: {}", old, now, e.toString());
        }
    }
}
