package dev.cloudsavesync.core;

import dev.cloudsavesync.core.archive.LocalBackups;
import dev.cloudsavesync.core.archive.SnapshotArchiver;
import dev.cloudsavesync.core.local.AtomicFiles;
import dev.cloudsavesync.core.local.DeviceInfo;
import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.local.UnsyncedMarker;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.CloudLayout;
import dev.cloudsavesync.core.model.SessionLock;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.model.WorldManifest;
import dev.cloudsavesync.core.storage.ProgressListener;
import dev.cloudsavesync.core.storage.RemoteFile;
import dev.cloudsavesync.core.storage.StorageException;
import dev.cloudsavesync.core.storage.StorageProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Sync operations for one world. Provider-agnostic and Hytale-agnostic: used by the plugin (load-time
 * restore, periodic uploads) and by the detached helper (upload after the server exits).
 *
 * <p>Callers serialize work per world with {@link dev.cloudsavesync.core.local.LocalSyncLock}.
 * All methods block on disk and network.
 */
public final class SyncEngine {

    /** Unpinned snapshots kept per world, including the current one. */
    public static final int KEEP_SNAPSHOTS = 5;
    /** Conflict copies and kept-aside versions retained per world. */
    public static final int KEEP_PINNED = 5;

    /**
     * Disk read limit for snapshots taken while the world is open (20 MiB/s: a ~90 MB world takes
     * ~5 s), so the game's own chunk I/O keeps priority even on a slow drive.
     */
    public static final long IN_SESSION_READ_BYTES_PER_SECOND = 20L * 1024 * 1024;

    public static final String REASON_EXIT = "exit";
    public static final String REASON_PERIODIC = "periodic";
    public static final String REASON_MANUAL = "manual";
    public static final String REASON_CONFLICT_COPY = "conflict-copy";

    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageProvider storage;
    private final WorldPaths paths;
    private final DeviceInfo device;
    private final Clock clock;
    private final SyncLog log;
    private final UnsyncedMarker marker;

    public SyncEngine(StorageProvider storage, WorldPaths paths, DeviceInfo device, Clock clock, SyncLog log) {
        this.storage = storage;
        this.paths = paths;
        this.device = device;
        this.clock = clock;
        this.log = log;
        this.marker = new UnsyncedMarker(paths.unsyncedMarkerFile(), clock);
    }

    public UnsyncedMarker marker() {
        return marker;
    }

    public WorldPaths paths() {
        return paths;
    }

    public DeviceInfo device() {
        return device;
    }

    public LocalState localState() throws IOException {
        return LocalState.load(paths.stateFile());
    }

    private void saveLocal(LocalState s) throws IOException {
        s.save(paths.stateFile());
    }

    // ---------------------------------------------------------------- reading the cloud

    public CloudState fetch() throws IOException {
        String key = paths.worldKey();
        String manifestName = CloudLayout.manifestName(key);
        String lockName = CloudLayout.lockName(key);
        String snapshotPrefix = "world." + CloudLayout.encode(key) + ".snap.";
        RemoteFile manifestFile = null;
        RemoteFile lockFile = null;
        List<RemoteFile> snapshotFiles = new ArrayList<>();
        for (RemoteFile f : storage.list()) {
            if (f.name().equals(manifestName)) {
                if (newer(f, manifestFile)) {
                    manifestFile = f;
                }
            } else if (f.name().equals(lockName)) {
                if (newer(f, lockFile)) {
                    lockFile = f;
                }
            } else if (f.name().startsWith(snapshotPrefix)) {
                snapshotFiles.add(f);
            }
        }
        WorldManifest manifest = null;
        if (manifestFile != null) {
            manifest = WorldManifest.fromJson(new String(storage.read(manifestFile.id()), StandardCharsets.UTF_8));
        }
        SessionLock lock = null;
        if (lockFile != null) {
            try {
                lock = SessionLock.fromJson(new String(storage.read(lockFile.id()), StandardCharsets.UTF_8));
            } catch (StorageException e) {
                if (e.kind() != StorageException.Kind.NOT_FOUND) {
                    throw e;
                }
            } catch (RuntimeException corrupt) {
                log.warn("ignoring unreadable session lock for " + key, corrupt);
            }
        }
        return new CloudState(manifest, manifestFile, lock, lockFile, snapshotFiles);
    }

    /** Snapshot zips older than this that the manifest doesn't reference are deleted. */
    static final java.time.Duration ORPHAN_AGE = java.time.Duration.ofDays(1);

    /**
     * Deletes zips the manifest no longer references (left by failed deletes or by lost manifest
     * races). The age limit keeps it away from another device's upload that hasn't been committed yet.
     */
    private void deleteOrphans(CloudState cloud, WorldManifest committed) {
        Set<String> referenced = new HashSet<>();
        committed.snapshots().forEach(s -> referenced.add(s.fileId()));
        Instant cutoff = clock.instant().minus(ORPHAN_AGE);
        for (RemoteFile f : cloud.snapshotFiles()) {
            if (!referenced.contains(f.id()) && f.modified() != null && f.modified().isBefore(cutoff)) {
                safeDelete(f.id());
            }
        }
    }

    private static boolean newer(RemoteFile candidate, RemoteFile current) {
        return current == null || (candidate.modified() != null && current.modified() != null
                && candidate.modified().isAfter(current.modified()));
    }

    public StartPlan planStart(CloudState cloud) throws IOException {
        return StartPlan.decide(true, cloud == null ? null : cloud.manifest(), localState(), marker.isSet());
    }

    public StartPlan planStartOffline() throws IOException {
        return StartPlan.decide(false, null, localState(), marker.isSet());
    }

    // ---------------------------------------------------------------- restoring

    /** Downloads {@code s} into the pending folder and verifies it. Doesn't touch the save. */
    public Path download(SnapshotInfo s, ProgressListener progress) throws IOException {
        Files.createDirectories(paths.pendingDir());
        Path zip = paths.pendingDir().resolve(s.id() + ".zip");
        Path part = paths.pendingDir().resolve(s.id() + ".zip.part");
        try {
            storage.download(s.fileId(), part, progress);
            String sha = SnapshotArchiver.sha256(part);
            if (!sha.equalsIgnoreCase(s.sha256Hex())) {
                throw new StorageException(StorageException.Kind.INTEGRITY,
                        "downloaded save from " + s.deviceName() + " is damaged (checksum mismatch)");
            }
            AtomicFiles.move(part, zip);
            return zip;
        } finally {
            AtomicFiles.deleteQuietly(part);
        }
    }

    /** Remembers a downloaded cloud version to apply at the next world load. */
    public void stagePending(SnapshotInfo s, long revision, Path zip) throws IOException {
        saveLocal(localState().withPendingRestore(new LocalState.Pending(revision, s.id(),
                zip.getFileName().toString(), s.sha256Hex(), s.deviceName(), s.createdAt())));
    }

    public Optional<Path> pendingZip() throws IOException {
        LocalState.Pending p = localState().pendingRestore();
        if (p == null) {
            return Optional.empty();
        }
        Path zip = paths.pendingDir().resolve(p.zipFile());
        return Files.exists(zip) ? Optional.of(zip) : Optional.empty();
    }

    /** Drops a staged pending restore (missing file, player chose keep-local...). */
    public void discardPending() throws IOException {
        LocalState s = localState();
        if (s.pendingRestore() != null) {
            AtomicFiles.deleteQuietly(paths.pendingDir().resolve(s.pendingRestore().zipFile()));
            saveLocal(s.withPendingRestore(null));
        }
    }

    /**
     * Replaces the local world with a verified cloud zip. Only call while the world is NOT loaded.
     * Backs up the current save first and returns the backup's path.
     */
    public Path applyRestore(Path zip, String sha256Hex, long revision, String snapshotId) throws IOException {
        Path backup = LocalBackups.backup(paths.saveDir(), paths.backupsDir(), "before-cloud-restore",
                clock.instant());
        log.info("backed up local save to " + backup);
        SnapshotArchiver.restoreUniverse(zip, sha256Hex, paths.saveDir());
        saveLocal(localState().synced(revision, snapshotId, clock.instant()));
        marker.clear();
        AtomicFiles.deleteQuietly(zip);
        log.info("restored cloud revision " + revision + " (snapshot " + snapshotId + ")");
        return backup;
    }

    /** Records that the local world now matches cloud {@code revision} (after an import). */
    public void recordSynced(long revision, String snapshotId) throws IOException {
        saveLocal(LocalState.NEVER_SYNCED.synced(revision, snapshotId, clock.instant()));
        marker.clear();
    }

    // ---------------------------------------------------------------- uploading

    public enum UploadOutcome {
        /** Became the current cloud version. */
        HEAD,
        /** The cloud moved on meanwhile: stored as a pinned conflict copy, current version untouched. */
        CONFLICT_COPY
    }

    public record UploadResult(UploadOutcome outcome, SnapshotInfo snapshot, long revision, boolean markerCleared) {
    }

    /**
     * Zips the save and uploads it.
     *
     * @param worldClosed true only when no server has the world open (helper after exit, or before
     *                    load). Only then can a confirmed upload clear the unsynced marker: a running
     *                    world keeps changing after the zip is taken.
     */
    public UploadResult uploadSave(String reason, boolean worldClosed, ProgressListener progress) throws IOException {
        OptionalLong generation = marker.generation(); // read BEFORE zipping
        Files.createDirectories(paths.tmpDir());
        String id = newSnapshotId();
        Path zipPath = paths.tmpDir().resolve(CloudLayout.encode(paths.worldKey()) + "." + id + ".zip");
        try {
            long t0 = System.nanoTime();
            SnapshotArchiver.Result zip = SnapshotArchiver.createSnapshot(paths.saveDir(), zipPath,
                    worldClosed ? 0 : IN_SESSION_READ_BYTES_PER_SECOND);
            log.info("zipped " + zip.fileCount() + " files, " + zip.sizeBytes() / (1024 * 1024) + " MB in "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms" + (worldClosed ? "" : " (read-limited, world open)"));
            return uploadArchive(id, zip, reason, worldClosed ? generation : OptionalLong.empty(), progress);
        } finally {
            AtomicFiles.deleteQuietly(zipPath);
        }
    }

    /** Uploads an already-made snapshot zip and commits it to the manifest. */
    public UploadResult uploadArchive(String id, SnapshotArchiver.Result zip, String reason,
                                      OptionalLong clearMarkerGeneration, ProgressListener progress)
            throws IOException {
        String key = paths.worldKey();
        long t0 = System.nanoTime();
        RemoteFile uploaded = storage.create(CloudLayout.snapshotName(key, id), CloudLayout.snapshotProps(key, id),
                zip.zip(), progress);
        long ms = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
        log.info("sent " + zip.sizeBytes() / (1024 * 1024) + " MB in " + ms / 1000 + " s ("
                + zip.sizeBytes() * 1000 / ms / 1024 + " KiB/s)");
        boolean sizeOk = uploaded.size() == zip.sizeBytes();
        boolean md5Ok = uploaded.md5Hex() == null || uploaded.md5Hex().equalsIgnoreCase(zip.md5Hex());
        if (!sizeOk || !md5Ok) {
            safeDelete(uploaded.id());
            throw new StorageException(StorageException.Kind.INTEGRITY,
                    "upload was corrupted in transit (cloud copy didn't match); will retry");
        }

        // Re-read right before committing so we see uploads other devices made meanwhile.
        CloudState cloud = fetch();
        WorldManifest manifest = cloud.manifest() != null ? cloud.manifest() : WorldManifest.empty(key);
        LocalState local = localState();
        SnapshotInfo snap = new SnapshotInfo(id, uploaded.id(), clock.instant(), device.deviceId(),
                device.deviceName(), zip.sizeBytes(), zip.md5Hex(), zip.sha256Hex(), local.baseRevision(), false,
                reason);

        boolean becomesHead = local.baseRevision() >= manifest.revision()
                && local.conflict() == null && local.useCloud() == null && local.pendingRestore() == null;

        Set<String> obsolete = new HashSet<>();
        WorldManifest next;
        if (becomesHead) {
            next = manifest.withHead(snap);
        } else {
            // Keep both: our version goes in as a pinned conflict copy, replacing our older one.
            snap = new SnapshotInfo(snap.id(), snap.fileId(), snap.createdAt(), snap.deviceId(), snap.deviceName(),
                    snap.sizeBytes(), snap.md5Hex(), snap.sha256Hex(), snap.baseRevision(), true,
                    REASON_CONFLICT_COPY);
            for (SnapshotInfo s : manifest.snapshots()) {
                if (s.pinned() && REASON_CONFLICT_COPY.equals(s.reason()) && s.deviceId().equals(device.deviceId())
                        && !s.id().equals(manifest.headSnapshotId())) {
                    obsolete.add(s.id());
                }
            }
            next = manifest.without(obsolete).withSnapshot(snap);
        }
        WorldManifest.Pruned pruned = next.prune(KEEP_SNAPSHOTS, KEEP_PINNED);
        writeManifest(cloud.manifestFile(), pruned.manifest());

        for (SnapshotInfo s : manifest.snapshots()) {
            if (obsolete.contains(s.id())) {
                safeDelete(s.fileId());
            }
        }
        for (SnapshotInfo s : pruned.removed()) {
            safeDelete(s.fileId());
        }
        deleteOrphans(cloud, pruned.manifest());

        boolean cleared = false;
        if (becomesHead) {
            saveLocal(localState().synced(pruned.manifest().revision(), snap.id(), clock.instant()));
            if (clearMarkerGeneration.isPresent()) {
                cleared = marker.clearIf(clearMarkerGeneration.getAsLong());
            }
            log.info("uploaded " + snap.id() + " as cloud revision " + pruned.manifest().revision()
                    + (cleared ? " (local marked as synced)" : ""));
            return new UploadResult(UploadOutcome.HEAD, snap, pruned.manifest().revision(), cleared);
        }

        SnapshotInfo cloudHead = manifest.head().orElse(null);
        if (cloudHead != null && localState().conflict() == null) {
            saveLocal(localState().withConflict(new LocalState.Conflict(manifest.revision(), cloudHead.id(),
                    cloudHead.deviceName(), cloudHead.createdAt(), clock.instant())));
        }
        log.info("cloud has a newer version; uploaded " + snap.id() + " as a conflict copy, unsynced marker kept");
        return new UploadResult(UploadOutcome.CONFLICT_COPY, snap, manifest.revision(), false);
    }

    private void writeManifest(RemoteFile existing, WorldManifest manifest) throws IOException {
        byte[] json = manifest.toJson().getBytes(StandardCharsets.UTF_8);
        if (existing != null) {
            storage.overwrite(existing.id(), json);
        } else {
            storage.create(CloudLayout.manifestName(paths.worldKey()),
                    CloudLayout.props(paths.worldKey(), CloudLayout.KIND_MANIFEST), json);
        }
    }

    // ---------------------------------------------------------------- conflict resolution

    /** Records a conflict found at load time so the player can be asked. */
    public void recordConflict(StartPlan plan) throws IOException {
        SnapshotInfo head = plan.snapshot();
        saveLocal(localState().withConflict(new LocalState.Conflict(plan.cloudRevision(), head.id(),
                head.deviceName(), head.createdAt(), clock.instant())));
    }

    /**
     * Player chose the cloud version: it replaces the local world at the next load (after a local
     * backup). This session's version is still uploaded as a conflict copy at exit.
     */
    public void chooseCloud(CloudState cloud) throws IOException {
        SnapshotInfo head = requireHead(cloud);
        saveLocal(localState().withUseCloud(new LocalState.CloudChoice(cloud.manifest().revision(), head.id())));
    }

    /**
     * Player chose this device's version: the cloud's current version is pinned so it is never
     * pruned, and the next upload from this device becomes current.
     */
    public void chooseLocal(CloudState cloud) throws IOException {
        SnapshotInfo head = requireHead(cloud);
        writeManifest(cloud.manifestFile(), cloud.manifest().withPinned(head.id(), true));
        discardPending();
        saveLocal(localState().adoptingBase(cloud.manifest().revision()));
        marker.mark("keep-local");
    }

    private static SnapshotInfo requireHead(CloudState cloud) throws StorageException {
        if (cloud.manifest() == null || cloud.manifest().head().isEmpty()) {
            throw new StorageException(StorageException.Kind.NOT_FOUND, "this world has no cloud version");
        }
        return cloud.manifest().head().get();
    }

    // ---------------------------------------------------------------- session lock

    /** Another device's fresh session lock, if any. Our own device's locks never count. */
    public Optional<SessionLock> foreignLock(CloudState cloud) {
        SessionLock l = cloud.lock();
        if (l == null || l.deviceId().equals(device.deviceId()) || !l.isFresh(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(l);
    }

    /** Writes (or refreshes) our session lock. Returns the lock file so heartbeats can overwrite it. */
    public RemoteFile writeSessionLock(SessionLock lock, RemoteFile existing) throws IOException {
        byte[] json = lock.toJson().getBytes(StandardCharsets.UTF_8);
        if (existing != null) {
            try {
                return storage.overwrite(existing.id(), json);
            } catch (StorageException e) {
                if (e.kind() != StorageException.Kind.NOT_FOUND) {
                    throw e;
                }
            }
        }
        return storage.create(CloudLayout.lockName(paths.worldKey()),
                CloudLayout.props(paths.worldKey(), CloudLayout.KIND_LOCK), json);
    }

    public SessionLock newSessionLock(String sessionId) {
        Instant now = clock.instant();
        return new SessionLock(device.deviceId(), device.deviceName(), sessionId, now, now);
    }

    /** Removes the session lock if it is still ours (another session may have taken it since). */
    public void releaseSessionLock(String sessionId) throws IOException {
        CloudState cloud = fetch();
        if (cloud.lock() != null && cloud.lockFile() != null && sessionId.equals(cloud.lock().sessionId())) {
            safeDelete(cloud.lockFile().id());
        }
    }

    // ---------------------------------------------------------------- helpers

    public String newSnapshotId() {
        byte[] r = new byte[3];
        RANDOM.nextBytes(r);
        return ID_TIME.format(clock.instant()) + "-" + HexFormat.of().formatHex(r);
    }

    private void safeDelete(String fileId) {
        try {
            storage.delete(fileId);
        } catch (IOException | RuntimeException e) {
            log.warn("could not delete old cloud file " + fileId + " (an orphan clean-up will retry it)", e);
        }
    }

    /** Newest first, for status displays. */
    public static List<SnapshotInfo> history(WorldManifest manifest) {
        return manifest.snapshots().stream()
                .sorted(Comparator.comparing(SnapshotInfo::createdAt).reversed())
                .toList();
    }
}
