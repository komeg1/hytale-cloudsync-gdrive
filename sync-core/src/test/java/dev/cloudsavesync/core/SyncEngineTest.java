package dev.cloudsavesync.core;

import dev.cloudsavesync.core.SyncEngine.UploadOutcome;
import dev.cloudsavesync.core.SyncEngine.UploadResult;
import dev.cloudsavesync.core.StartPlan.Action;
import dev.cloudsavesync.core.local.DeviceInfo;
import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.SessionLock;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.storage.ProgressListener;
import dev.cloudsavesync.core.storage.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncEngineTest {

    private static final String WORLD = "My World";
    private static final SyncLog QUIET = new SyncLog() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message, Throwable error) {
        }
    };

    @TempDir
    Path tmp;

    private MutableClock clock;
    private InMemoryStorageProvider cloud;
    private SyncEngine pc;
    private SyncEngine deck;

    @BeforeEach
    void setUp() throws IOException {
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        cloud = new InMemoryStorageProvider(clock);
        pc = device("pc", "Gaming PC");
        deck = device("deck", "Steam Deck");
    }

    private SyncEngine device(String dir, String name) throws IOException {
        Path userData = tmp.resolve(dir).resolve("UserData");
        Path save = userData.resolve("Saves").resolve(WORLD);
        writeWorld(save, name + " v0");
        WorldPaths paths = WorldPaths.of(save, userData.resolve("CloudSaveSync"));
        return new SyncEngine(cloud, paths, new DeviceInfo(dir + "-id", name), clock, QUIET);
    }

    private static void writeWorld(Path save, String content) throws IOException {
        Files.createDirectories(save.resolve("universe/worlds/default/chunks"));
        Files.createDirectories(save.resolve("universe/players"));
        Files.createDirectories(save.resolve("logs"));
        Files.writeString(save.resolve("universe/worlds/default/chunks/0.0.region.bin"), content);
        Files.writeString(save.resolve("universe/players/p.json"), "{\"who\":\"" + content + "\"}");
        Files.writeString(save.resolve("config.json"), "{}");
        Files.writeString(save.resolve("logs/server.log"), "log " + content);
    }

    private static String world(SyncEngine e) throws IOException {
        return Files.readString(e.paths().universeDir().resolve("worlds/default/chunks/0.0.region.bin"));
    }

    private UploadResult exitUpload(SyncEngine e) throws IOException {
        e.marker().mark("session");
        clock.advance(Duration.ofMinutes(1));
        return e.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE);
    }

    private void restoreHead(SyncEngine e) throws IOException {
        StartPlan plan = e.planStart(e.fetch());
        assertEquals(Action.RESTORE, plan.action(), plan.why());
        Path zip = e.download(plan.snapshot(), ProgressListener.NONE);
        e.applyRestore(zip, plan.snapshot().sha256Hex(), plan.cloudRevision(), plan.snapshot().id());
    }

    @Test
    void firstUploadBecomesRevisionOneAndClearsMarker() throws IOException {
        UploadResult r = exitUpload(pc);

        assertEquals(UploadOutcome.HEAD, r.outcome());
        assertEquals(1, r.revision());
        assertTrue(r.markerCleared());
        assertFalse(pc.marker().isSet());
        assertEquals(1L, pc.localState().lastSyncedRevision());
        assertEquals(Action.UP_TO_DATE, pc.planStart(pc.fetch()).action());
    }

    @Test
    void uploadWhileWorldRunningNeverClearsMarker() throws IOException {
        pc.marker().mark("session");
        UploadResult r = pc.uploadSave(SyncEngine.REASON_PERIODIC, false, ProgressListener.NONE);

        assertEquals(UploadOutcome.HEAD, r.outcome());
        assertFalse(r.markerCleared());
        assertTrue(pc.marker().isSet());
    }

    @Test
    void snapshotExcludesLogsAndRestoreKeepsLocalTopLevelFiles() throws IOException {
        exitUpload(pc);
        deck.chooseCloud(deck.fetch());
        Files.writeString(deck.paths().saveDir().resolve("config.json"), "{\"deck\":true}");

        restoreHead(deck);

        assertEquals("Gaming PC v0", world(deck));
        assertEquals("{\"deck\":true}", Files.readString(deck.paths().saveDir().resolve("config.json")));
        assertEquals("log Steam Deck v0", Files.readString(deck.paths().saveDir().resolve("logs/server.log")));
    }

    @Test
    void neverSyncedCopyOfAnExistingCloudWorldIsAConflictNotARestore() throws IOException {
        exitUpload(pc);

        StartPlan plan = deck.planStart(deck.fetch());

        assertEquals(Action.CONFLICT, plan.action());
        assertEquals("Steam Deck v0", world(deck));
    }

    @Test
    void choosingCloudRestoresWithBackupAndClearsMarker() throws IOException {
        exitUpload(pc);
        deck.marker().mark("session");
        deck.chooseCloud(deck.fetch());

        restoreHead(deck);

        assertEquals("Gaming PC v0", world(deck));
        assertFalse(deck.marker().isSet());
        assertEquals(1L, deck.localState().lastSyncedRevision());
        assertNull(deck.localState().useCloud());
        try (Stream<Path> backups = Files.list(deck.paths().backupsDir())) {
            assertEquals(1, backups.count(), "the overwritten local world must be backed up");
        }
    }

    @Test
    void cleanDeviceRestoresNewerCloudVersion() throws IOException {
        exitUpload(pc);
        deck.chooseCloud(deck.fetch());
        restoreHead(deck);

        writeWorld(pc.paths().saveDir(), "Gaming PC v1");
        exitUpload(pc);

        restoreHead(deck);
        assertEquals("Gaming PC v1", world(deck));
        assertEquals(2L, deck.localState().lastSyncedRevision());
    }

    @Test
    void unsyncedChangesAreNeverOverwrittenAndBecomeAConflictCopy() throws IOException {
        exitUpload(pc);
        deck.chooseCloud(deck.fetch());
        restoreHead(deck);

        writeWorld(pc.paths().saveDir(), "Gaming PC v1");
        exitUpload(pc);
        writeWorld(deck.paths().saveDir(), "Steam Deck offline edits");
        deck.marker().mark("session");

        StartPlan plan = deck.planStart(deck.fetch());
        assertEquals(Action.CONFLICT, plan.action());
        assertEquals("Steam Deck offline edits", world(deck));

        UploadResult r = deck.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE);
        assertEquals(UploadOutcome.CONFLICT_COPY, r.outcome());
        assertTrue(r.snapshot().pinned());
        assertTrue(deck.marker().isSet(), "a conflict copy must not count as synced");
        assertNotNull(deck.localState().conflict());

        var manifest = pc.fetch().manifest();
        assertEquals(2, manifest.revision(), "the current version must not change");
        assertEquals("Gaming PC", manifest.head().orElseThrow().deviceName());
        assertEquals(Action.UP_TO_DATE, pc.planStart(pc.fetch()).action(), "other devices must not restore it");

        // A second exit upload replaces this device's earlier conflict copy instead of piling up.
        clock.advance(Duration.ofMinutes(5));
        deck.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE);
        long copies = pc.fetch().manifest().snapshots().stream().filter(SnapshotInfo::pinned).count();
        assertEquals(1, copies);
    }

    @Test
    void keepLocalPinsCloudVersionAndNextUploadBecomesCurrent() throws IOException {
        exitUpload(pc);
        deck.chooseCloud(deck.fetch());
        restoreHead(deck);
        writeWorld(pc.paths().saveDir(), "Gaming PC v1");
        exitUpload(pc);
        writeWorld(deck.paths().saveDir(), "Steam Deck edits");
        deck.marker().mark("session");
        deck.recordConflict(deck.planStart(deck.fetch()));

        deck.chooseLocal(deck.fetch());
        UploadResult r = exitUpload(deck);

        assertEquals(UploadOutcome.HEAD, r.outcome());
        assertEquals(3, r.revision());
        var manifest = deck.fetch().manifest();
        assertTrue(manifest.snapshots().stream().anyMatch(s -> s.deviceName().equals("Gaming PC") && s.pinned()),
                "PC's version stays in history, pinned");
        restoreHead(pc);
        assertEquals("Steam Deck edits", world(pc));
    }

    @Test
    void keepsOnlyTheLastFiveSnapshots() throws IOException {
        for (int i = 0; i < 8; i++) {
            writeWorld(pc.paths().saveDir(), "v" + i);
            exitUpload(pc);
        }
        var manifest = pc.fetch().manifest();
        assertEquals(8, manifest.revision());
        assertEquals(5, manifest.snapshots().size());
        long zips = cloud.names().stream().filter(n -> n.endsWith(".zip")).count();
        assertEquals(5, zips, "pruned snapshots must be deleted from the cloud too");
    }

    @Test
    void corruptedUploadIsRejectedAndNothingCommitted() throws IOException {
        exitUpload(pc);
        pc.marker().mark("session");
        cloud.corruptNextUpload = true;

        StorageException e = assertThrows(StorageException.class,
                () -> pc.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE));

        assertEquals(StorageException.Kind.INTEGRITY, e.kind());
        assertTrue(pc.marker().isSet());
        assertEquals(1, pc.fetch().manifest().revision());
        assertEquals(1, cloud.names().stream().filter(n -> n.endsWith(".zip")).count());
    }

    @Test
    void offlineUploadKeepsMarkerForNextTime() throws IOException {
        pc.marker().mark("session");
        cloud.offline = true;

        StorageException e = assertThrows(StorageException.class,
                () -> pc.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE));

        assertEquals(StorageException.Kind.OFFLINE, e.kind());
        assertTrue(pc.marker().isSet());
        assertEquals(Action.OFFLINE, pc.planStartOffline().action());
    }

    @Test
    void sessionStartedDuringUploadKeepsMarker() throws IOException {
        pc.marker().mark("session");
        cloud.duringNextUpload = () -> {
            try {
                clock.advance(Duration.ofSeconds(1));
                pc.marker().mark("new session");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        };

        UploadResult r = pc.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE);

        assertEquals(UploadOutcome.HEAD, r.outcome());
        assertFalse(r.markerCleared());
        assertTrue(pc.marker().isSet());
    }

    @Test
    void damagedCloudCopyIsNeverApplied() throws IOException {
        exitUpload(pc);
        deck.chooseCloud(deck.fetch());
        StartPlan plan = deck.planStart(deck.fetch());
        cloud.damage(plan.snapshot().fileId());

        StorageException e = assertThrows(StorageException.class,
                () -> deck.download(plan.snapshot(), ProgressListener.NONE));

        assertEquals(StorageException.Kind.INTEGRITY, e.kind());
        assertEquals("Steam Deck v0", world(deck));
    }

    @Test
    void pendingRestoreIsAppliedAtNextLoadEvenOffline() throws IOException {
        exitUpload(pc);
        deck.chooseCloud(deck.fetch());
        StartPlan plan = deck.planStart(deck.fetch());
        Path zip = deck.download(plan.snapshot(), ProgressListener.NONE);
        deck.stagePending(plan.snapshot(), plan.cloudRevision(), zip);
        deck.marker().mark("session that was told to reopen");
        cloud.offline = true;

        StartPlan next = deck.planStartOffline();
        assertEquals(Action.APPLY_PENDING, next.action());
        LocalState.Pending p = deck.localState().pendingRestore();
        deck.applyRestore(deck.pendingZip().orElseThrow(), p.sha256Hex(), p.revision(), p.snapshotId());

        assertEquals("Gaming PC v0", world(deck));
        assertNull(deck.localState().pendingRestore());
    }

    @Test
    void sessionLocksFromOtherDevicesWarnUntilStale() throws IOException {
        SessionLock lock = deck.newSessionLock("s1");
        deck.writeSessionLock(lock, null);

        assertTrue(pc.foreignLock(pc.fetch()).isPresent());
        assertTrue(deck.foreignLock(deck.fetch()).isEmpty(), "own lock is not a warning");

        clock.advance(SessionLock.STALE_AFTER.plusSeconds(1));
        assertTrue(pc.foreignLock(pc.fetch()).isEmpty());

        deck.releaseSessionLock("other-session");
        assertNotNull(pc.fetch().lock(), "only the owning session may release");
        deck.releaseSessionLock("s1");
        assertNull(pc.fetch().lock());
    }

    @Test
    void worldNamesWithSpecialCharactersDontCollide() throws IOException {
        Path userData = tmp.resolve("pc").resolve("UserData");
        // "a+" encodes to "a%2B"; without escaping '_' both would become "a_2B".
        for (String name : List.of("a_2B", "a+")) {
            Path save = userData.resolve("Saves").resolve(name);
            writeWorld(save, name);
            SyncEngine e = new SyncEngine(cloud, WorldPaths.of(save, userData.resolve("CloudSaveSync")),
                    new DeviceInfo("pc-id", "Gaming PC"), clock, QUIET);
            exitUpload(e);
        }
        long manifests = cloud.names().stream().filter(n -> n.endsWith(".manifest.json")).count();
        assertEquals(2, manifests);
    }
}
