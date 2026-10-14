package dev.cloudsavesync.core.model;

import dev.cloudsavesync.core.json.Json;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One uploaded zip of a world.
 *
 * @param baseRevision the manifest revision the uploading device had synced when it made this
 *                     snapshot (0 if it had never synced)
 * @param pinned       exempt from "keep the last 5" pruning: conflict copies and versions a player
 *                     chose to keep
 * @param reason       why it was made: exit, periodic, manual, conflict-copy
 */
public record SnapshotInfo(String id, String fileId, Instant createdAt, String deviceId, String deviceName,
                           long sizeBytes, String md5Hex, String sha256Hex, long baseRevision,
                           boolean pinned, String reason) {

    public SnapshotInfo withPinned(boolean pinned) {
        return new SnapshotInfo(id, fileId, createdAt, deviceId, deviceName, sizeBytes, md5Hex, sha256Hex,
                baseRevision, pinned, reason);
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("fileId", fileId);
        m.put("createdAt", createdAt.toString());
        m.put("deviceId", deviceId);
        m.put("deviceName", deviceName);
        m.put("sizeBytes", sizeBytes);
        m.put("md5", md5Hex);
        m.put("sha256", sha256Hex);
        m.put("baseRevision", baseRevision);
        m.put("pinned", pinned);
        m.put("reason", reason);
        return m;
    }

    static SnapshotInfo fromJson(Map<String, Object> m) {
        return new SnapshotInfo(
                Json.string(m, "id"),
                Json.string(m, "fileId"),
                Instant.parse(Json.string(m, "createdAt")),
                Json.string(m, "deviceId"),
                Json.string(m, "deviceName"),
                Json.number(m, "sizeBytes"),
                Json.optString(m, "md5"),
                Json.string(m, "sha256"),
                Json.number(m, "baseRevision"),
                Json.optBoolean(m, "pinned", false),
                Json.optString(m, "reason"));
    }
}
