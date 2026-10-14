package dev.cloudsavesync.core;

import dev.cloudsavesync.core.model.SessionLock;
import dev.cloudsavesync.core.model.WorldManifest;
import dev.cloudsavesync.core.storage.RemoteFile;

import java.util.List;

/**
 * One read of a world's cloud files.
 *
 * @param manifest      null if the world isn't in the cloud
 * @param manifestFile  null if there is no manifest file
 * @param lock          null if no device holds the session lock
 * @param snapshotFiles every snapshot zip of this world, referenced by the manifest or not
 */
public record CloudState(WorldManifest manifest, RemoteFile manifestFile, SessionLock lock, RemoteFile lockFile,
                         List<RemoteFile> snapshotFiles) {

    public CloudState {
        snapshotFiles = snapshotFiles == null ? List.of() : List.copyOf(snapshotFiles);
    }
}
