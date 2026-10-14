package dev.cloudsavesync.spike;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Read-only probe of the save files. It never writes to, truncates, creates or deletes anything.
 *
 * <p>{@link Mode#LOCK} is only used at setup(), before the world is loaded. On Windows locks are
 * mandatory, so every lock is released immediately after it is acquired. {@link Mode#OPEN_ONLY} is
 * used once the world is running: it only opens and closes each file, with the default
 * Java sharing flags (read, write and delete all shared), so it can't block the game's own writes.
 */
final class FileProbe {

    enum Mode { LOCK, OPEN_ONLY }

    private static final int MAX_DETAIL_LINES = 200;
    private static final int MAX_DEPTH = 8;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    private FileProbe() {
    }

    static void run(SpikeLog log, String label, Mode mode, List<Path> roots) {
        long start = System.nanoTime();
        Set<Path> openHere = openFilesOfThisProcess();
        Map<String, Integer> counts = new TreeMap<>();
        int files = 0;
        int detailLines = 0;

        log.log("probe:" + label + ":begin", "mode=" + mode + " roots=" + roots
                + (openHere == null ? "" : " procFdOpenFiles=" + openHere.size()));

        Set<Path> seen = new HashSet<>();
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path absRoot = root.toAbsolutePath().normalize();
            if (!Files.isDirectory(absRoot)) {
                log.detail("root " + absRoot + " (missing)");
                continue;
            }
            log.detail("root " + absRoot);
            for (Path file : listFiles(absRoot)) {
                if (!seen.add(file) || file.equals(log.file())) {
                    continue;
                }
                files++;
                String state = probeFile(file, mode);
                String counted = state.startsWith("open-failed") ? "open-failed" : state;
                if (openHere != null) {
                    String fd = openHere.contains(file) ? "open-in-this-process" : "not-open-in-this-process";
                    state += " " + fd;
                    counted += " " + fd;
                }
                counts.merge(counted, 1, Integer::sum);
                if (detailLines < MAX_DETAIL_LINES) {
                    detailLines++;
                    log.detail(describe(absRoot, file) + "  " + state);
                }
            }
        }
        if (files > detailLines) {
            log.detail("... " + (files - detailLines) + " more files not listed individually");
        }
        log.log("probe:" + label + ":end", "files=" + files + " states=" + counts
                + " took=" + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static List<Path> listFiles(Path root) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            walk.filter(Files::isRegularFile).sorted().forEach(p -> out.add(p.toAbsolutePath().normalize()));
        } catch (IOException | RuntimeException e) {
            out.clear();
        }
        return out;
    }

    private static String describe(Path root, Path file) {
        String rel = root.relativize(file).toString();
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return rel + "  size=" + a.size() + "  mtime=" + a.lastModifiedTime();
        } catch (IOException e) {
            return rel + "  (attributes unreadable: " + e.getClass().getSimpleName() + ")";
        }
    }

    private static String probeFile(Path file, Mode mode) {
        return mode == Mode.LOCK ? lockProbe(file) : openProbe(file);
    }

    /** Setup-only: open for write, try a lock and release it immediately. */
    private static String lockProbe(Path file) {
        String writers = WINDOWS ? " " + WindowsShareProbe.writers(file) : "";
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                return "locked-in-this-JVM" + writers;
            }
            if (lock == null) {
                return "locked-by-other-process" + writers;
            }
            lock.release();
            return "free" + writers;
        } catch (IOException e) {
            return "open-failed: " + reason(e) + writers;
        }
    }

    /** Running-world probe: open and close only, never lock. */
    private static String openProbe(Path file) {
        try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.WRITE)) {
            return "open-rw-ok";
        } catch (IOException writeFailure) {
            try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ)) {
                return "open-read-only (write: " + reason(writeFailure) + ")";
            } catch (IOException e) {
                return "open-failed: " + reason(e);
            }
        }
    }

    static String reason(IOException e) {
        if (e instanceof FileSystemException fse && fse.getReason() != null) {
            return e.getClass().getSimpleName() + " " + fse.getReason();
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
    }

    /**
     * Linux only (Steam Deck): resolves /proc/self/fd, which lists exactly which files this server
     * process holds open. Reading it doesn't touch the save files. Returns null on other platforms.
     */
    private static Set<Path> openFilesOfThisProcess() {
        Path fdDir = Path.of("/proc/self/fd");
        if (!Files.isDirectory(fdDir)) {
            return null;
        }
        Set<Path> open = new LinkedHashSet<>();
        try (Stream<Path> fds = Files.list(fdDir)) {
            fds.forEach(fd -> {
                try {
                    open.add(Files.readSymbolicLink(fd).toAbsolutePath().normalize());
                } catch (IOException | RuntimeException ignored) {
                    // fd closed while listing
                }
            });
        } catch (IOException e) {
            return null;
        }
        return open;
    }

    /**
     * Windows only, setup only: opens the file for read while refusing to share write access, then
     * closes it. This fails with a sharing violation if any handle (in any process, including
     * this one) has the file open for writing. It's the only way in plain Java to see whether a
     * file is already open on Windows. Kept in its own class so a JRE without the
     * jdk.unsupported module degrades to "writers=n/a" instead of failing to load the probe.
     */
    private static final class WindowsShareProbe {
        static String writers(Path file) {
            try {
                OpenOption noShareWrite = com.sun.nio.file.ExtendedOpenOption.NOSHARE_WRITE;
                try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.READ, noShareWrite)) {
                    return "writers=none";
                } catch (IOException e) {
                    return "writers=present(" + reason(e) + ")";
                }
            } catch (LinkageError | RuntimeException e) {
                return "writers=n/a(" + e.getClass().getSimpleName() + ")";
            }
        }
    }
}
