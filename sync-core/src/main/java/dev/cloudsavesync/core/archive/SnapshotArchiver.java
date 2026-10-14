package dev.cloudsavesync.core.archive;

import dev.cloudsavesync.core.local.AtomicFiles;
import dev.cloudsavesync.core.storage.StorageException;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Zips a save folder into a snapshot and restores the world from one.
 *
 * <p>A snapshot holds the whole save folder minus logs, telemetry, the game's own backup folder and
 * this mod's per-device data. A restore only replaces {@code universe/} (the world and player
 * data): the save's top-level files ({@code config.json} etc.) are already loaded by the server
 * when a restore runs and are rewritten by it on exit, so they stay as the local copy.
 */
public final class SnapshotArchiver {

    static final String UNIVERSE = "universe";
    /** Restore staging folders, beside universe/ in the save. */
    static final String UNIVERSE_INCOMING = "universe.cloudsync-incoming";
    static final String UNIVERSE_OLD = "universe.cloudsync-old";

    /** Top-level paths (relative, '/'-separated) never put in a snapshot. */
    public static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "logs/", "telemetry/", "backup/",
            "mods/CloudSaveSync_CloudSaveSync/",
            UNIVERSE_INCOMING + "/", UNIVERSE_OLD + "/");

    private SnapshotArchiver() {
    }

    public record Result(Path zip, long sizeBytes, String md5Hex, String sha256Hex, int fileCount) {
    }

    /**
     * Top-level files never put in a snapshot. {@code .world.lock} is held exclusively by the
     * running server (Windows refuses to read it mid-session) and is per-machine runtime state.
     */
    public static final Set<String> EXCLUDED_FILES = Set.of(".world.lock");

    public static boolean isExcluded(String relativePath) {
        String p = relativePath.replace('\\', '/');
        if (EXCLUDED_FILES.contains(p)) {
            return true;
        }
        for (String prefix : EXCLUDED_PREFIXES) {
            if (p.startsWith(prefix)) {
                return true;
            }
        }
        return p.endsWith(".tmp");
    }

    /** Zips {@code saveDir} into {@code outZip} at full speed (world closed). */
    public static Result createSnapshot(Path saveDir, Path outZip) throws IOException {
        return createSnapshot(saveDir, outZip, 0);
    }

    /**
     * Zips {@code saveDir} into {@code outZip}, hashing the zip bytes as they are written.
     *
     * <p>Built to stay out of the game's way when it runs during play: region files (most of the
     * save, already compressed by the game; deflating them saved only ~6%) are stored without
     * compression, so the zip costs almost no CPU; reads go through one reused 64 KiB buffer, so no
     * garbage piles up; and {@code maxBytesPerSecond} caps disk reads so the game's own chunk
     * loading and saving keep priority.
     *
     * @param maxBytesPerSecond read limit, or 0 for unlimited
     */
    public static Result createSnapshot(Path saveDir, Path outZip, long maxBytesPerSecond) throws IOException {
        Path root = saveDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root.resolve(UNIVERSE))) {
            throw new IOException("no universe/ folder in " + root + "; is this a Hytale save?");
        }
        Files.createDirectories(outZip.toAbsolutePath().getParent());
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isExcluded(rel(root, dir) + "/")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !isExcluded(rel(root, file))) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);

        MessageDigest md5 = digest("MD5");
        MessageDigest sha256 = digest("SHA-256");
        try (OutputStream raw = Files.newOutputStream(outZip);
             OutputStream hashed = new DigestOutputStream(new DigestOutputStream(raw, md5), sha256);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(hashed, 1 << 16))) {
            byte[] buffer = new byte[1 << 16];
            Throttle throttle = new Throttle(maxBytesPerSecond);
            for (Path file : files) {
                String name = rel(root, file);
                ZipEntry entry = new ZipEntry(name);
                entry.setLastModifiedTime(Files.getLastModifiedTime(file));
                zip.setLevel(isPrecompressed(name) ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED);
                zip.putNextEntry(entry);
                try (InputStream in = Files.newInputStream(file)) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, n);
                        throttle.consumed(n);
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            AtomicFiles.deleteQuietly(outZip);
            throw e;
        }
        HexFormat hex = HexFormat.of();
        return new Result(outZip, Files.size(outZip), hex.formatHex(md5.digest()), hex.formatHex(sha256.digest()),
                files.size());
    }

    /** Hytale region files are compressed by the game already. */
    static boolean isPrecompressed(String relativePath) {
        return relativePath.endsWith(".region.bin");
    }

    /** Sleeps as needed to keep the average read rate under a limit. */
    private static final class Throttle {
        private final long bytesPerSecond;
        private final long start = System.nanoTime();
        private long total;

        Throttle(long bytesPerSecond) {
            this.bytesPerSecond = bytesPerSecond;
        }

        void consumed(int bytes) throws IOException {
            if (bytesPerSecond <= 0) {
                return;
            }
            total += bytes;
            long dueNanos = total * 1_000_000_000L / bytesPerSecond;
            long aheadMillis = (dueNanos - (System.nanoTime() - start)) / 1_000_000;
            if (aheadMillis > 5) {
                try {
                    Thread.sleep(aheadMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("snapshot interrupted");
                }
            }
        }
    }

    /**
     * Replaces {@code saveDir/universe} with the {@code universe/} entries of {@code zip}.
     * Verifies the zip's SHA-256 first. The new world is fully extracted beside the old one before
     * anything is swapped, and the old one is put back if the swap fails.
     */
    public static void restoreUniverse(Path zip, String expectedSha256, Path saveDir) throws IOException {
        String actual = sha256(zip);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new StorageException(StorageException.Kind.INTEGRITY,
                    "downloaded save is damaged (checksum mismatch); nothing was changed");
        }
        Path root = saveDir.toAbsolutePath().normalize();
        Path incoming = root.resolve(UNIVERSE_INCOMING);
        Path old = root.resolve(UNIVERSE_OLD);
        Path universe = root.resolve(UNIVERSE);
        AtomicFiles.deleteRecursively(incoming);
        AtomicFiles.deleteRecursively(old);

        int extracted = extractUniverse(zip, incoming);
        if (extracted == 0) {
            AtomicFiles.deleteRecursively(incoming);
            throw new StorageException(StorageException.Kind.INTEGRITY,
                    "cloud save contains no world data; nothing was changed");
        }

        boolean hadUniverse = Files.exists(universe);
        if (hadUniverse) {
            Files.move(universe, old);
        }
        try {
            Files.move(incoming, universe);
        } catch (IOException e) {
            if (hadUniverse) {
                Files.move(old, universe);
            }
            throw e;
        }
        AtomicFiles.deleteRecursively(old);
    }

    private static int extractUniverse(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        Path targetRoot = target.toAbsolutePath().normalize();
        int count = 0;
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (!name.startsWith(UNIVERSE + "/")) {
                    continue;
                }
                String inner = name.substring(UNIVERSE.length() + 1);
                if (inner.isEmpty()) {
                    continue;
                }
                Path out = targetRoot.resolve(inner).normalize();
                if (!out.startsWith(targetRoot)) {
                    throw new StorageException(StorageException.Kind.INTEGRITY, "unsafe path in snapshot: " + name);
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(zin, out);
                FileTime t = e.getLastModifiedTime();
                if (t != null) {
                    Files.setLastModifiedTime(out, t);
                }
                count++;
            }
        }
        return count;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest d = digest("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(d.digest());
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
