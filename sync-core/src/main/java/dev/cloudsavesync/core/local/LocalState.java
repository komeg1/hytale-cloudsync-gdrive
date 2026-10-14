package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This device's sync bookkeeping for one world. Stored outside the save, never uploaded.
 *
 * @param lastSyncedRevision manifest revision the local world matches, or null if never synced
 * @param conflict           a newer cloud version found while this device had unsynced changes
 * @param useCloud           the player chose the cloud version; apply it at the next world load
 * @param pendingRestore     a cloud version that finished downloading too late for the last load
 */
public record LocalState(Long lastSyncedRevision, String lastSyncedSnapshotId, Instant lastSyncAt,
                         Conflict conflict, CloudChoice useCloud, Pending pendingRestore) {

    public static final LocalState NEVER_SYNCED = new LocalState(null, null, null, null, null, null);

    public record Conflict(long cloudRevision, String cloudSnapshotId, String cloudDeviceName,
                           Instant cloudCreatedAt, Instant detectedAt) {
    }

    public record CloudChoice(long revision, String snapshotId) {
    }

    /** @param zipFile file name inside {@link WorldPaths#pendingDir()} */
    public record Pending(long revision, String snapshotId, String zipFile, String sha256Hex,
                          String deviceName, Instant createdAt) {
    }

    public LocalState synced(long revision, String snapshotId, Instant at) {
        return new LocalState(revision, snapshotId, at, null, null, null);
    }

    public LocalState withConflict(Conflict c) {
        return new LocalState(lastSyncedRevision, lastSyncedSnapshotId, lastSyncAt, c, useCloud, pendingRestore);
    }

    public LocalState withUseCloud(CloudChoice choice) {
        return new LocalState(lastSyncedRevision, lastSyncedSnapshotId, lastSyncAt, conflict, choice, pendingRestore);
    }

    public LocalState withPendingRestore(Pending p) {
        return new LocalState(lastSyncedRevision, lastSyncedSnapshotId, lastSyncAt, conflict, useCloud, p);
    }

    /** Keep-local: treat the cloud revision as our base so our next upload replaces it as current. */
    public LocalState adoptingBase(long cloudRevision) {
        return new LocalState(cloudRevision, lastSyncedSnapshotId, lastSyncAt, null, null, null);
    }

    public long baseRevision() {
        return lastSyncedRevision == null ? 0 : lastSyncedRevision;
    }

    // ---- persistence ----

    public static LocalState load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return NEVER_SYNCED;
        }
        Map<String, Object> m = Json.parseObject(AtomicFiles.readString(file));
        Map<String, Object> c = Json.optObject(m, "conflict");
        Map<String, Object> u = Json.optObject(m, "useCloud");
        Map<String, Object> p = Json.optObject(m, "pendingRestore");
        return new LocalState(
                Json.optNumber(m, "lastSyncedRevision"),
                Json.optString(m, "lastSyncedSnapshotId"),
                instant(Json.optString(m, "lastSyncAt")),
                c == null ? null : new Conflict(Json.number(c, "cloudRevision"), Json.string(c, "cloudSnapshotId"),
                        Json.string(c, "cloudDeviceName"), instant(Json.string(c, "cloudCreatedAt")),
                        instant(Json.string(c, "detectedAt"))),
                u == null ? null : new CloudChoice(Json.number(u, "revision"), Json.string(u, "snapshotId")),
                p == null ? null : new Pending(Json.number(p, "revision"), Json.string(p, "snapshotId"),
                        Json.string(p, "zipFile"), Json.string(p, "sha256"), Json.string(p, "deviceName"),
                        instant(Json.string(p, "createdAt"))));
    }

    public void save(Path file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastSyncedRevision", lastSyncedRevision);
        m.put("lastSyncedSnapshotId", lastSyncedSnapshotId);
        m.put("lastSyncAt", lastSyncAt == null ? null : lastSyncAt.toString());
        if (conflict != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("cloudRevision", conflict.cloudRevision());
            c.put("cloudSnapshotId", conflict.cloudSnapshotId());
            c.put("cloudDeviceName", conflict.cloudDeviceName());
            c.put("cloudCreatedAt", conflict.cloudCreatedAt().toString());
            c.put("detectedAt", conflict.detectedAt().toString());
            m.put("conflict", c);
        }
        if (useCloud != null) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("revision", useCloud.revision());
            u.put("snapshotId", useCloud.snapshotId());
            m.put("useCloud", u);
        }
        if (pendingRestore != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("revision", pendingRestore.revision());
            p.put("snapshotId", pendingRestore.snapshotId());
            p.put("zipFile", pendingRestore.zipFile());
            p.put("sha256", pendingRestore.sha256Hex());
            p.put("deviceName", pendingRestore.deviceName());
            p.put("createdAt", pendingRestore.createdAt().toString());
            m.put("pendingRestore", p);
        }
        AtomicFiles.writeString(file, Json.write(m));
    }

    private static Instant instant(String s) {
        return s == null ? null : Instant.parse(s);
    }
}
