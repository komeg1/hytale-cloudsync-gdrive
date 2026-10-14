package dev.cloudsavesync.core;

import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.model.WorldManifest;

/**
 * What to do when a world is about to load. Pure decision logic, see {@link #decide}.
 *
 * @param snapshot the cloud snapshot involved (RESTORE, CONFLICT), else null
 */
public record StartPlan(Action action, SnapshotInfo snapshot, long cloudRevision, String why) {

    public enum Action {
        /** Couldn't reach the cloud: play the local copy, sync later. */
        OFFLINE,
        /** The cloud has no copy of this world yet: the next upload creates it. */
        NOTHING_IN_CLOUD,
        /** The local copy is the current cloud version (local may also have newer, unsynced changes). */
        UP_TO_DATE,
        /** The local copy is newer than what the cloud says is current (cloud history was lost). */
        LOCAL_AHEAD,
        /** Replace the local world with {@link #snapshot()} before it loads. */
        RESTORE,
        /** A cloud version downloaded during the previous session is waiting: apply it now. */
        APPLY_PENDING,
        /** Cloud is newer AND this device has unsynced changes: keep both, ask the player. */
        CONFLICT
    }

    /**
     * Never returns RESTORE over unsynced local changes unless the player chose the cloud version
     * ({@code useCloud}) or was told a downloaded version would replace this session
     * ({@code pendingRestore}); in both cases the caller backs the local copy up first.
     *
     * @param online   whether the cloud could be read
     * @param manifest the cloud manifest, or null if the world isn't in the cloud
     * @param unsynced whether the unsynced-changes marker is set
     */
    public static StartPlan decide(boolean online, WorldManifest manifest, LocalState local, boolean unsynced) {
        SnapshotInfo head = manifest == null ? null : manifest.head().orElse(null);

        if (local.pendingRestore() != null) {
            if (online && head != null && manifest.revision() > local.pendingRestore().revision()) {
                return new StartPlan(Action.RESTORE, head, manifest.revision(),
                        "a newer cloud version replaced the one downloaded last time");
            }
            return new StartPlan(Action.APPLY_PENDING, null, local.pendingRestore().revision(),
                    "cloud version downloaded during the last session");
        }
        if (!online) {
            return new StartPlan(Action.OFFLINE, null, -1, "cloud unreachable");
        }
        if (head == null) {
            return new StartPlan(Action.NOTHING_IN_CLOUD, null, 0, "world not in the cloud yet");
        }
        long cloudRev = manifest.revision();
        if (local.useCloud() != null) {
            return new StartPlan(Action.RESTORE, head, cloudRev, "player chose the cloud version");
        }
        if (local.lastSyncedRevision() == null) {
            // Same world name in the cloud, but this copy was never synced: they may be unrelated.
            return new StartPlan(Action.CONFLICT, head, cloudRev, "this copy has never been synced");
        }
        long mine = local.lastSyncedRevision();
        if (cloudRev > mine) {
            return unsynced
                    ? new StartPlan(Action.CONFLICT, head, cloudRev, "cloud is newer and this device has unsynced changes")
                    : new StartPlan(Action.RESTORE, head, cloudRev, "cloud is newer");
        }
        if (cloudRev < mine) {
            return new StartPlan(Action.LOCAL_AHEAD, head, cloudRev, "cloud is behind this device");
        }
        return new StartPlan(Action.UP_TO_DATE, head, cloudRev, unsynced ? "local changes to upload" : "in sync");
    }
}
