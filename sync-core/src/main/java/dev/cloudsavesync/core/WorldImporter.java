package dev.cloudsavesync.core;

import dev.cloudsavesync.core.archive.SnapshotArchiver;
import dev.cloudsavesync.core.local.AtomicFiles;
import dev.cloudsavesync.core.local.DeviceInfo;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.CloudLayout;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.model.WorldManifest;
import dev.cloudsavesync.core.storage.ProgressListener;
import dev.cloudsavesync.core.storage.RemoteFile;
import dev.cloudsavesync.core.storage.StorageException;
import dev.cloudsavesync.core.storage.StorageProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Brings cloud worlds this device doesn't have into its Saves folder, so they appear in the game's
 * world list. A server plugin can't run in the main menu, so this runs while any world is open.
 *
 * <p>Never overwrites anything: a world is imported only if no save folder with that name exists.
 * Worlds that were synced here before and whose folder is now gone were deleted on purpose by the
 * player, and are not brought back.
 */
public final class WorldImporter {

    public record CloudWorld(String worldKey, WorldManifest manifest) {

        public SnapshotInfo head() {
            return manifest.head().orElseThrow();
        }
    }

    private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final StorageProvider storage;
    private final Path savesDir;
    private final Path stateRoot;
    private final DeviceInfo device;
    private final Clock clock;
    private final SyncLog log;

    public WorldImporter(StorageProvider storage, Path savesDir, Path stateRoot, DeviceInfo device, Clock clock,
                         SyncLog log) {
        this.storage = storage;
        this.savesDir = savesDir.toAbsolutePath().normalize();
        this.stateRoot = stateRoot;
        this.device = device;
        this.clock = clock;
        this.log = log;
    }

    /** Every world in the cloud that has a current version, by name. */
    public List<CloudWorld> cloudWorlds() throws IOException {
        Map<String, RemoteFile> newestByName = new HashMap<>();
        for (RemoteFile f : storage.list()) {
            if (f.name().startsWith("world.") && f.name().endsWith(".manifest.json")) {
                newestByName.merge(f.name(), f, (a, b) -> b.modified() != null && a.modified() != null
                        && b.modified().isAfter(a.modified()) ? b : a);
            }
        }
        List<CloudWorld> out = new ArrayList<>();
        for (RemoteFile f : newestByName.values()) {
            try {
                WorldManifest m = WorldManifest.fromJson(new String(storage.read(f.id()), StandardCharsets.UTF_8));
                if (m.head().isPresent()) {
                    out.add(new CloudWorld(m.worldKey(), m));
                }
            } catch (StorageException e) {
                if (e.kind() != StorageException.Kind.NOT_FOUND) {
                    throw e;
                }
            } catch (RuntimeException unreadable) {
                log.warn("skipping unreadable cloud manifest " + f.name(), unreadable);
            }
        }
        out.sort(Comparator.comparing(CloudWorld::worldKey, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The cloud worlds that should be imported now (see the class comment for what is skipped). */
    public List<CloudWorld> missing(List<CloudWorld> cloud) {
        List<CloudWorld> out = new ArrayList<>();
        for (CloudWorld w : cloud) {
            if (!isSafeFolderName(w.worldKey())) {
                log.info("cloud world '" + w.worldKey() + "' has a name this system can't use as a folder; skipped");
                continue;
            }
            if (Files.exists(savesDir.resolve(w.worldKey()))) {
                continue;
            }
            if (Files.exists(paths(w.worldKey()).stateFile())) {
                log.info("cloud world '" + w.worldKey() + "' was deleted on this device; not downloading it again");
                continue;
            }
            out.add(w);
        }
        return out;
    }

    /**
     * Downloads the current version of {@code w} and installs it as {@code Saves/<name>}. The save is
     * extracted outside the Saves folder first and moved in whole, so the game never lists a
     * half-written world.
     *
     * @param maxBytesPerSecond disk write limit while a world is running, 0 for unlimited
     * @return the new save folder
     */
    public Path importWorld(CloudWorld w, ProgressListener progress, long maxBytesPerSecond) throws IOException {
        WorldPaths paths = paths(w.worldKey());
        SyncEngine engine = new SyncEngine(storage, paths, device, clock, log);
        SnapshotInfo head = w.head();
        Path zip = engine.download(head, progress);
        Path staging = paths.tmpDir().resolve("import-" + CloudLayout.encode(w.worldKey()) + "-" + UUID.randomUUID());
        try {
            int files = SnapshotArchiver.extractSave(zip, head.sha256Hex(), staging, maxBytesPerSecond);
            Path target = paths.saveDir();
            if (Files.exists(target)) {
                throw new IOException("a world named '" + w.worldKey() + "' appeared meanwhile; not overwriting it");
            }
            Files.move(staging, target);
            engine.recordSynced(w.manifest().revision(), head.id(), storage.account().id());
            log.info("imported cloud world '" + w.worldKey() + "' (revision " + w.manifest().revision() + ", "
                    + files + " files, from " + head.deviceName() + ")");
            return target;
        } finally {
            AtomicFiles.deleteQuietly(zip);
            AtomicFiles.deleteRecursively(staging);
        }
    }

    private WorldPaths paths(String worldKey) {
        return new WorldPaths(savesDir.resolve(worldKey), stateRoot, worldKey);
    }

    /** A name that is a plain, valid folder name on Windows, macOS and Linux. */
    public static boolean isSafeFolderName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.endsWith(" ") || name.endsWith(".") || name.length() > 200) {
            return false;
        }
        for (char c : name.toCharArray()) {
            if (c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                return false;
            }
        }
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) {
            return false;
        }
        try {
            return Path.of(name).getNameCount() == 1;
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
