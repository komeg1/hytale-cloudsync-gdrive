package dev.cloudsavesync.helper;

import dev.cloudsavesync.core.SyncEngine;
import dev.cloudsavesync.core.SyncLog;
import dev.cloudsavesync.core.WorldImporter;
import dev.cloudsavesync.core.local.DeviceInfo;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.storage.StorageProvider;
import dev.cloudsavesync.gdrive.GoogleAccountStore;
import dev.cloudsavesync.gdrive.GoogleAuth;
import dev.cloudsavesync.gdrive.GoogleClients;
import dev.cloudsavesync.gdrive.GoogleDriveStorageProvider;

import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Wires the Google provider to the sync engine. The only place that names a concrete provider:
 * adding OneDrive or Dropbox means choosing a different {@link StorageProvider} here.
 *
 * <p>No Hytale imports: the detached helper uses this too.
 */
public final class SyncServices {

    private final GoogleAuth auth;
    private final StorageProvider storage;
    private final DeviceInfo device;
    private final Clock clock;

    private SyncServices(GoogleAuth auth, StorageProvider storage, DeviceInfo device, Clock clock) {
        this.auth = auth;
        this.storage = storage;
        this.device = device;
        this.clock = clock;
    }

    /** Empty if this build has no Google OAuth clients (made without secrets.properties). */
    public static Optional<SyncServices> create(java.nio.file.Path stateRoot, Executor background) throws IOException {
        Optional<GoogleClients> clients = GoogleClients.load();
        if (clients.isEmpty()) {
            return Optional.empty();
        }
        Clock clock = Clock.systemUTC();
        GoogleAuth auth = new GoogleAuth(clients.get(), GoogleAccountStore.in(stateRoot), clock, background);
        return Optional.of(new SyncServices(auth, new GoogleDriveStorageProvider(auth),
                DeviceInfo.loadOrCreate(stateRoot), clock));
    }

    public GoogleAuth auth() {
        return auth;
    }

    public DeviceInfo device() {
        return device;
    }

    public Clock clock() {
        return clock;
    }

    public SyncEngine engine(WorldPaths paths, SyncLog log) {
        return new SyncEngine(storage, paths, device, clock, log);
    }

    public WorldImporter importer(java.nio.file.Path savesDir, java.nio.file.Path stateRoot, SyncLog log) {
        return new WorldImporter(storage, savesDir, stateRoot, device, clock, log);
    }
}
