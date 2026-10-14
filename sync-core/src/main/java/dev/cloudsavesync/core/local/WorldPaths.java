package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.model.CloudLayout;

import java.nio.file.Path;

/**
 * Where everything for one world lives on this device.
 *
 * <p>The save folder is synced. Everything else (sign-in token, sync state, the unsynced marker,
 * backups, temp zips) lives under {@code stateRoot}, which is outside every save folder so it is
 * never zipped or uploaded.
 *
 * @param saveDir   e.g. {@code .../UserData/Saves/My World}
 * @param stateRoot e.g. {@code .../UserData/CloudSaveSync}
 * @param worldKey  the world's identity across devices: its save folder name
 */
public record WorldPaths(Path saveDir, Path stateRoot, String worldKey) {

    public WorldPaths {
        saveDir = saveDir.toAbsolutePath().normalize();
        stateRoot = stateRoot.toAbsolutePath().normalize();
    }

    /** Uses the save folder's name as the world key. */
    public static WorldPaths of(Path saveDir, Path stateRoot) {
        Path abs = saveDir.toAbsolutePath().normalize();
        return new WorldPaths(abs, stateRoot, abs.getFileName().toString());
    }

    public Path universeDir() {
        return saveDir.resolve("universe");
    }

    public Path worldStateDir() {
        return stateRoot.resolve("worlds").resolve(CloudLayout.encode(worldKey));
    }

    public Path stateFile() {
        return worldStateDir().resolve("state.json");
    }

    public Path unsyncedMarkerFile() {
        return worldStateDir().resolve("unsynced.json");
    }

    /** Held while reading the save folder or uploading, by the helper or the plugin. */
    public Path syncLockFile() {
        return worldStateDir().resolve("sync.lock");
    }

    public Path helperStatusFile() {
        return worldStateDir().resolve("helper-status.json");
    }

    public Path helperLogFile() {
        return worldStateDir().resolve("helper.log");
    }

    /** Cloud versions downloaded but not yet applied. */
    public Path pendingDir() {
        return worldStateDir().resolve("pending");
    }

    /** Local copies of what a restore overwrote. */
    public Path backupsDir() {
        return stateRoot.resolve("backups").resolve(CloudLayout.encode(worldKey));
    }

    public Path tmpDir() {
        return stateRoot.resolve("tmp");
    }
}
