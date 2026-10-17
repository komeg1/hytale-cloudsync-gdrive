package dev.cloudsavesync.core.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

/**
 * Tells apart two worlds that had the same folder name. Sync state is kept per world name, so a
 * world deleted and created again under its old name would otherwise inherit the old world's
 * state: it would start syncing on its own, or even get the old cloud version applied over it.
 *
 * <p>Each save gets a random id in {@link WorldPaths#worldIdFile()} (inside the save, in a folder
 * snapshots never include, so every device's copy has its own id), and the world's state folder
 * records the id it belongs to. When they differ, the state is from another world and is dropped.
 */
public final class WorldIdentity {

    /** State that describes one particular world. Logs and local backups are kept. */
    private static final List<String> STATE_FILES = List.of("sync.json", "state.json", "unsynced.json",
            "helper-status.json");

    private WorldIdentity() {
    }

    /**
     * Makes sure the save and the state folder agree on which world this is, discarding state left
     * by an earlier world with the same name.
     *
     * @return true if earlier state was discarded
     */
    public static boolean claim(WorldPaths paths) throws IOException {
        String saveId = read(paths.worldIdFile());
        String recorded = read(paths.recordedWorldIdFile());
        boolean stale;
        if (saveId == null) {
            saveId = UUID.randomUUID().toString();
            // A save without an id is new, or from before ids existed. State for an older world is
            // stale; from before ids existed, it's stale if this save folder was created after it.
            stale = recorded != null || createdAfterState(paths);
            AtomicFiles.writeString(paths.worldIdFile(), saveId);
        } else {
            stale = recorded != null && !recorded.equals(saveId);
        }
        if (stale) {
            for (String name : STATE_FILES) {
                AtomicFiles.deleteQuietly(paths.worldStateDir().resolve(name));
            }
            AtomicFiles.deleteRecursively(paths.pendingDir());
        }
        if (!saveId.equals(recorded)) {
            AtomicFiles.writeString(paths.recordedWorldIdFile(), saveId);
        }
        return stale;
    }

    private static boolean createdAfterState(WorldPaths paths) throws IOException {
        FileTime stateWritten = null;
        for (String name : List.of("sync.json", "state.json")) {
            Path f = paths.worldStateDir().resolve(name);
            if (Files.exists(f)) {
                FileTime t = Files.getLastModifiedTime(f);
                stateWritten = stateWritten == null || t.compareTo(stateWritten) > 0 ? t : stateWritten;
            }
        }
        if (stateWritten == null || !Files.isDirectory(paths.saveDir())) {
            return false;
        }
        FileTime created = Files.readAttributes(paths.saveDir(), BasicFileAttributes.class).creationTime();
        return created.compareTo(stateWritten) > 0;
    }

    private static String read(Path f) throws IOException {
        if (!Files.exists(f)) {
            return null;
        }
        String id = AtomicFiles.readString(f).trim();
        return id.isEmpty() ? null : id;
    }
}
