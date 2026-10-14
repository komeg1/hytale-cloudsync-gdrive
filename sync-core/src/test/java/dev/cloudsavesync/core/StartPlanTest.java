package dev.cloudsavesync.core;

import dev.cloudsavesync.core.StartPlan.Action;
import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.model.WorldManifest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartPlanTest {

    private static final Instant T = Instant.parse("2026-10-14T12:00:00Z");

    private static WorldManifest cloudAtRevision(long revision) {
        WorldManifest m = WorldManifest.empty("w");
        for (int i = 1; i <= revision; i++) {
            m = m.withHead(new SnapshotInfo("s" + i, "f" + i, T.plusSeconds(i), "pc", "PC", 1, null, "sha", i - 1,
                    false, "exit"));
        }
        return m;
    }

    private static LocalState syncedAt(long revision) {
        return LocalState.NEVER_SYNCED.synced(revision, "s" + revision, T);
    }

    @Test
    void offline() {
        assertEquals(Action.OFFLINE, StartPlan.decide(false, null, syncedAt(1), true).action());
    }

    @Test
    void nothingInCloud() {
        assertEquals(Action.NOTHING_IN_CLOUD, StartPlan.decide(true, null, LocalState.NEVER_SYNCED, true).action());
        assertEquals(Action.NOTHING_IN_CLOUD,
                StartPlan.decide(true, WorldManifest.empty("w"), LocalState.NEVER_SYNCED, false).action());
    }

    @Test
    void upToDateWithOrWithoutLocalChanges() {
        assertEquals(Action.UP_TO_DATE, StartPlan.decide(true, cloudAtRevision(3), syncedAt(3), false).action());
        assertEquals(Action.UP_TO_DATE, StartPlan.decide(true, cloudAtRevision(3), syncedAt(3), true).action());
    }

    @Test
    void newerCloudRestoresOnlyWhenLocalIsClean() {
        StartPlan clean = StartPlan.decide(true, cloudAtRevision(4), syncedAt(3), false);
        assertEquals(Action.RESTORE, clean.action());
        assertEquals("s4", clean.snapshot().id());
        assertEquals(4, clean.cloudRevision());

        assertEquals(Action.CONFLICT, StartPlan.decide(true, cloudAtRevision(4), syncedAt(3), true).action());
    }

    @Test
    void neverSyncedLocalCopyIsAConflictEvenWithoutMarker() {
        assertEquals(Action.CONFLICT,
                StartPlan.decide(true, cloudAtRevision(1), LocalState.NEVER_SYNCED, false).action());
    }

    @Test
    void cloudBehindLocal() {
        assertEquals(Action.LOCAL_AHEAD, StartPlan.decide(true, cloudAtRevision(2), syncedAt(5), false).action());
    }

    @Test
    void playerChoiceForCloudOverridesUnsyncedChanges() {
        LocalState chose = syncedAt(3).withUseCloud(new LocalState.CloudChoice(4, "s4"));
        StartPlan p = StartPlan.decide(true, cloudAtRevision(5), chose, true);
        assertEquals(Action.RESTORE, p.action());
        assertEquals("s5", p.snapshot().id(), "always restores the newest cloud version");
    }

    @Test
    void pendingRestoreAppliesOfflineButYieldsToNewerCloud() {
        LocalState pending = syncedAt(3).withPendingRestore(
                new LocalState.Pending(4, "s4", "s4.zip", "sha", "PC", T));

        assertEquals(Action.APPLY_PENDING, StartPlan.decide(false, null, pending, true).action());
        assertEquals(Action.APPLY_PENDING, StartPlan.decide(true, cloudAtRevision(4), pending, true).action());
        StartPlan newer = StartPlan.decide(true, cloudAtRevision(6), pending, true);
        assertEquals(Action.RESTORE, newer.action());
        assertEquals("s6", newer.snapshot().id());
    }
}
