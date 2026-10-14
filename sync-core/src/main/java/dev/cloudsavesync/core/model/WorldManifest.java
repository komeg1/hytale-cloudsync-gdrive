package dev.cloudsavesync.core.model;

import dev.cloudsavesync.core.json.Json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-world JSON document in the cloud listing a world's snapshots and which one is current.
 *
 * <p>{@code revision} increases by one every time the head (the current version of the world)
 * changes, and only then. Devices remember the revision they last synced, so "the cloud is newer
 * than my copy" is {@code cloud.revision > mine} without trusting anyone's clock. Adding a conflict
 * copy doesn't change the head, so it doesn't make other devices think there's a newer save.
 */
public record WorldManifest(int formatVersion, String worldKey, long revision, String headSnapshotId,
                            List<SnapshotInfo> snapshots, int keepSnapshots) {

    public static final int FORMAT_VERSION = 1;
    /** Normal (unpinned) copies kept when the world doesn't say otherwise. */
    public static final int DEFAULT_KEEP = 5;
    public static final int MIN_KEEP = 1;
    public static final int MAX_KEEP = 20;

    public WorldManifest {
        snapshots = List.copyOf(snapshots);
    }

    public static WorldManifest empty(String worldKey) {
        return new WorldManifest(FORMAT_VERSION, worldKey, 0, null, List.of(), 0);
    }

    /**
     * How many normal copies this world keeps. Stored in the manifest so every device prunes with the
     * same number; 0 (older manifests) means {@link #DEFAULT_KEEP}.
     */
    public int keep() {
        return keepSnapshots <= 0 ? DEFAULT_KEEP : Math.max(MIN_KEEP, Math.min(MAX_KEEP, keepSnapshots));
    }

    public WorldManifest withKeep(int keep) {
        return new WorldManifest(formatVersion, worldKey, revision, headSnapshotId, snapshots,
                Math.max(MIN_KEEP, Math.min(MAX_KEEP, keep)));
    }

    public Optional<SnapshotInfo> head() {
        return find(headSnapshotId);
    }

    public Optional<SnapshotInfo> find(String snapshotId) {
        if (snapshotId == null) {
            return Optional.empty();
        }
        return snapshots.stream().filter(s -> s.id().equals(snapshotId)).findFirst();
    }

    /** Adds {@code s} as the new current version of the world. */
    public WorldManifest withHead(SnapshotInfo s) {
        List<SnapshotInfo> list = new ArrayList<>(snapshots);
        list.add(s);
        return new WorldManifest(formatVersion, worldKey, revision + 1, s.id(), list, keepSnapshots);
    }

    /** Adds {@code s} without changing which version is current (conflict copies). */
    public WorldManifest withSnapshot(SnapshotInfo s) {
        List<SnapshotInfo> list = new ArrayList<>(snapshots);
        list.add(s);
        return new WorldManifest(formatVersion, worldKey, revision, headSnapshotId, list, keepSnapshots);
    }

    public WorldManifest withPinned(String snapshotId, boolean pinned) {
        List<SnapshotInfo> list = snapshots.stream()
                .map(s -> s.id().equals(snapshotId) ? s.withPinned(pinned) : s)
                .toList();
        return new WorldManifest(formatVersion, worldKey, revision, headSnapshotId, list, keepSnapshots);
    }

    public WorldManifest without(Set<String> snapshotIds) {
        List<SnapshotInfo> list = snapshots.stream()
                .filter(s -> !snapshotIds.contains(s.id()) || s.id().equals(headSnapshotId))
                .toList();
        return new WorldManifest(formatVersion, worldKey, revision, headSnapshotId, list, keepSnapshots);
    }

    public record Pruned(WorldManifest manifest, List<SnapshotInfo> removed) {
    }

    /**
     * Keeps the head, the newest {@code keepUnpinned} unpinned snapshots (the head counts toward
     * that number when it is unpinned) and the newest {@code keepPinned} pinned ones.
     */
    public Pruned prune(int keepUnpinned, int keepPinned) {
        Comparator<SnapshotInfo> newestFirst = Comparator.comparing(SnapshotInfo::createdAt)
                .thenComparing(SnapshotInfo::id).reversed();
        Optional<SnapshotInfo> head = head();
        int unpinnedBudget = keepUnpinned - (head.isPresent() && !head.get().pinned() ? 1 : 0);

        List<SnapshotInfo> removed = new ArrayList<>();
        int unpinnedKept = 0;
        int pinnedKept = 0;
        for (SnapshotInfo s : snapshots.stream().sorted(newestFirst).toList()) {
            if (s.id().equals(headSnapshotId)) {
                continue;
            }
            if (s.pinned()) {
                if (pinnedKept++ >= keepPinned) {
                    removed.add(s);
                }
            } else if (unpinnedKept++ >= unpinnedBudget) {
                removed.add(s);
            }
        }
        Set<String> removedIds = new java.util.HashSet<>();
        removed.forEach(s -> removedIds.add(s.id()));
        return new Pruned(without(removedIds), List.copyOf(removed));
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formatVersion", formatVersion);
        m.put("worldKey", worldKey);
        m.put("revision", revision);
        m.put("headSnapshotId", headSnapshotId);
        if (keepSnapshots > 0) {
            m.put("keepSnapshots", keepSnapshots);
        }
        m.put("snapshots", snapshots.stream().map(SnapshotInfo::toJson).toList());
        return Json.write(m);
    }

    @SuppressWarnings("unchecked")
    public static WorldManifest fromJson(String json) {
        Map<String, Object> m = Json.parseObject(json);
        long format = Json.number(m, "formatVersion");
        if (format > FORMAT_VERSION) {
            throw new IllegalStateException("manifest format " + format
                    + " is newer than this version of the mod understands; please update the mod");
        }
        List<SnapshotInfo> snaps = new ArrayList<>();
        for (Object o : Json.optArray(m, "snapshots")) {
            snaps.add(SnapshotInfo.fromJson((Map<String, Object>) o));
        }
        return new WorldManifest((int) format, Json.string(m, "worldKey"), Json.number(m, "revision"),
                Json.optString(m, "headSnapshotId"), snaps,
                (int) Math.min(Integer.MAX_VALUE, Optional.ofNullable(Json.optNumber(m, "keepSnapshots")).orElse(0L)));
    }
}
