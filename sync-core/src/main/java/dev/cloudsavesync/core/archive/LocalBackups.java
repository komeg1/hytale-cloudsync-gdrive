package dev.cloudsavesync.core.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Stream;

/** Zips of what a restore overwrote, kept on this device only. */
public final class LocalBackups {

    public static final int KEEP = 10;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC);

    private LocalBackups() {
    }

    /** Backs the save up as {@code <backupsDir>/<UTC time>-<label>.zip} and keeps the newest {@link #KEEP}. */
    public static Path backup(Path saveDir, Path backupsDir, String label, Instant now) throws IOException {
        Files.createDirectories(backupsDir);
        Path zip = backupsDir.resolve(STAMP.format(now) + "-" + label + ".zip");
        SnapshotArchiver.createSnapshot(saveDir, zip);
        prune(backupsDir);
        return zip;
    }

    static void prune(Path backupsDir) throws IOException {
        List<Path> zips;
        try (Stream<Path> s = Files.list(backupsDir)) {
            zips = s.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList();
        }
        for (int i = 0; i < zips.size() - KEEP; i++) {
            Files.deleteIfExists(zips.get(i));
        }
    }
}
