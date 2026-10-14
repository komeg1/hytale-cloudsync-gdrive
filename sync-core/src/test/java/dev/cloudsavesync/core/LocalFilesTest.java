package dev.cloudsavesync.core;

import dev.cloudsavesync.core.archive.LocalBackups;
import dev.cloudsavesync.core.archive.SnapshotArchiver;
import dev.cloudsavesync.core.local.LocalSyncLock;
import dev.cloudsavesync.core.local.UnsyncedMarker;
import dev.cloudsavesync.core.local.UserDataLocator;
import dev.cloudsavesync.core.storage.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalFilesTest {

    @TempDir
    Path tmp;

    private Path save() throws IOException {
        Path save = tmp.resolve("UserData/Saves/World");
        Files.createDirectories(save.resolve("universe/players"));
        Files.createDirectories(save.resolve("mods/CloudSaveSync_CloudSaveSync"));
        Files.createDirectories(save.resolve("mods/OtherMod_OtherMod"));
        Files.createDirectories(save.resolve("logs"));
        Files.createDirectories(save.resolve("telemetry"));
        Files.createDirectories(save.resolve("backup"));
        Files.writeString(save.resolve("universe/players/p.json"), "player");
        Files.writeString(save.resolve("universe/events.json"), "events");
        Files.writeString(save.resolve("config.json"), "config");
        Files.writeString(save.resolve("mods/CloudSaveSync_CloudSaveSync/x.txt"), "ours");
        Files.writeString(save.resolve("mods/OtherMod_OtherMod/data.json"), "theirs");
        Files.writeString(save.resolve("logs/a.log"), "log");
        Files.writeString(save.resolve("telemetry/t"), "t");
        Files.writeString(save.resolve("backup/b.zip"), "b");
        Files.writeString(save.resolve(".world.lock"), "held by the running server");
        return save;
    }

    private static List<String> entries(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            z.stream().map(ZipEntry::getName).forEach(names::add);
        }
        return names;
    }

    @Test
    void snapshotContainsWorldAndOtherModsButNotLogsOrOurData() throws IOException {
        SnapshotArchiver.Result r = SnapshotArchiver.createSnapshot(save(), tmp.resolve("s.zip"));

        assertEquals(List.of("config.json", "mods/OtherMod_OtherMod/data.json", "universe/events.json",
                "universe/players/p.json"), entries(r.zip()));
        assertEquals(4, r.fileCount());
        assertEquals(SnapshotArchiver.sha256(r.zip()), r.sha256Hex());
        assertEquals(Files.size(r.zip()), r.sizeBytes());
    }

    @Test
    void snapshotRefusesAFolderThatIsNotASave() throws IOException {
        Path notASave = Files.createDirectories(tmp.resolve("empty"));
        assertThrows(IOException.class, () -> SnapshotArchiver.createSnapshot(notASave, tmp.resolve("x.zip")));
        assertFalse(Files.exists(tmp.resolve("x.zip")));
    }

    @Test
    void restoreReplacesUniverseOnlyAndRemovesFilesNotInSnapshot() throws IOException {
        Path save = save();
        SnapshotArchiver.Result r = SnapshotArchiver.createSnapshot(save, tmp.resolve("s.zip"));
        Files.writeString(save.resolve("universe/players/p.json"), "changed");
        Files.writeString(save.resolve("universe/new-local-file.json"), "new");
        Files.writeString(save.resolve("config.json"), "local config");

        SnapshotArchiver.restoreUniverse(r.zip(), r.sha256Hex(), save);

        assertEquals("player", Files.readString(save.resolve("universe/players/p.json")));
        assertFalse(Files.exists(save.resolve("universe/new-local-file.json")));
        assertEquals("local config", Files.readString(save.resolve("config.json")));
        try (Stream<Path> s = Files.list(save)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith("universe.cloudsync")),
                    "staging folders must be cleaned up");
        }
    }

    @Test
    void restoreWithWrongChecksumChangesNothing() throws IOException {
        Path save = save();
        SnapshotArchiver.Result r = SnapshotArchiver.createSnapshot(save, tmp.resolve("s.zip"));
        Files.writeString(save.resolve("universe/players/p.json"), "local");

        StorageException e = assertThrows(StorageException.class,
                () -> SnapshotArchiver.restoreUniverse(r.zip(), "0".repeat(64), save));

        assertEquals(StorageException.Kind.INTEGRITY, e.kind());
        assertEquals("local", Files.readString(save.resolve("universe/players/p.json")));
    }

    @Test
    void restoreRejectsPathTraversalEntries() throws IOException {
        Path save = save();
        Path evil = tmp.resolve("evil.zip");
        try (OutputStream out = Files.newOutputStream(evil); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("universe/ok.json"));
            z.write("ok".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("universe/../../../escaped.txt"));
            z.write("bad".getBytes());
            z.closeEntry();
        }

        assertThrows(StorageException.class,
                () -> SnapshotArchiver.restoreUniverse(evil, SnapshotArchiver.sha256(evil), save));

        assertEquals("player", Files.readString(save.resolve("universe/players/p.json")));
        assertFalse(Files.exists(tmp.resolve("UserData/escaped.txt")));
    }

    @Test
    void localBackupsKeepOnlyTheNewestTen() throws IOException {
        Path save = save();
        Path backups = tmp.resolve("backups");
        Instant t = Instant.parse("2026-10-14T12:00:00Z");
        for (int i = 0; i < 12; i++) {
            LocalBackups.backup(save, backups, "before-cloud-restore", t.plusSeconds(i));
        }
        try (Stream<Path> s = Files.list(backups)) {
            List<String> names = s.map(p -> p.getFileName().toString()).sorted().toList();
            assertEquals(LocalBackups.KEEP, names.size());
            assertTrue(names.getFirst().startsWith("2026-10-14_12-00-02"), names.getFirst());
        }
    }

    @Test
    void markerClearsOnlyTheGenerationThatWasUploaded() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        UnsyncedMarker m = new UnsyncedMarker(tmp.resolve("unsynced.json"), clock);
        assertFalse(m.isSet());

        long first = m.mark("session");
        long second = m.mark("session again");
        assertTrue(second > first);

        assertFalse(m.clearIf(first));
        assertTrue(m.isSet());
        assertTrue(m.clearIf(second));
        assertFalse(m.isSet());

        // Generations stay unique after a clear, so a stale reader can't clear a new mark.
        long third = m.mark("new session");
        assertTrue(third > second);
    }

    @Test
    void damagedMarkerStillCountsAsUnsynced() throws IOException {
        Path file = tmp.resolve("unsynced.json");
        Files.writeString(file, "{ not json");
        UnsyncedMarker m = new UnsyncedMarker(file, new MutableClock(Instant.EPOCH));
        assertTrue(m.isSet());
    }

    @Test
    void syncLockIsExclusiveAndReleasedOnClose() throws Exception {
        Path lockFile = tmp.resolve("w/sync.lock");
        Optional<LocalSyncLock> first = LocalSyncLock.tryAcquire(lockFile);
        assertTrue(first.isPresent());
        assertTrue(LocalSyncLock.tryAcquire(lockFile).isEmpty());
        assertTrue(LocalSyncLock.acquire(lockFile, Duration.ofMillis(300)).isEmpty());
        first.get().close();
        try (LocalSyncLock again = LocalSyncLock.tryAcquire(lockFile).orElseThrow()) {
            assertTrue(again != null);
        }
    }

    @Test
    void stateRootIsBesideSavesWhateverTheOs() {
        Path save = tmp.resolve("anything/UserData/Saves/My World");
        assertEquals(tmp.resolve("anything/UserData/CloudSaveSync").toAbsolutePath().normalize(),
                UserDataLocator.stateRootFor(save, "Linux", Map.of(), tmp).toAbsolutePath().normalize());
    }

    @Test
    void osDefaultsIncludingFlatpak() throws IOException {
        Path home = tmp.resolve("home");
        Path flatpak = Files.createDirectories(home.resolve(".var/app/com.hypixel.HytaleLauncher/data/Hytale/UserData"));

        assertEquals(Optional.of(flatpak), UserDataLocator.osDefault("Linux", Map.of(), home));
        assertEquals(Optional.of(Path.of("C:/Users/x/AppData/Roaming/Hytale/UserData")),
                UserDataLocator.osDefault("Windows 11", Map.of("APPDATA", "C:/Users/x/AppData/Roaming"), home));
        assertEquals(Optional.of(home.resolve("Library/Application Support/Hytale/UserData")),
                UserDataLocator.osDefault("Mac OS X", Map.of(), home));
        Path notASave = tmp.resolve("somewhere/else");
        assertEquals(flatpak.resolve(UserDataLocator.STATE_FOLDER_NAME),
                UserDataLocator.stateRootFor(notASave, "Linux", Map.of(), home));
    }
}
