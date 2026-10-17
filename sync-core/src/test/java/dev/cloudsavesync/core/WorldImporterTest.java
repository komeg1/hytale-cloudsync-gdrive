package dev.cloudsavesync.core;

import dev.cloudsavesync.core.local.DeviceInfo;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.storage.ProgressListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldImporterTest {

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
    private Path deckSaves;
    private WorldImporter deck;

    @BeforeEach
    void setUp() throws IOException {
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        cloud = new InMemoryStorageProvider(clock);
        for (String world : List.of("Alpha", "Beta World")) {
            uploadFromPc(world, world + " content");
        }
        Path deckUserData = tmp.resolve("deck/UserData");
        deckSaves = Files.createDirectories(deckUserData.resolve("Saves"));
        deck = new WorldImporter(cloud, deckSaves, deckUserData.resolve("CloudSaveSync"),
                new DeviceInfo("deck-id", "Steam Deck"), clock, QUIET);
    }

    private void uploadFromPc(String world, String content) throws IOException {
        Path userData = tmp.resolve("pc/UserData");
        Path save = userData.resolve("Saves").resolve(world);
        Files.createDirectories(save.resolve("universe/players"));
        Files.writeString(save.resolve("universe/players/p.json"), content);
        Files.writeString(save.resolve("config.json"), "{\"Mods\":{\"CloudSaveSync:CloudSaveSync\":{\"Enabled\":true}}}");
        Files.writeString(save.resolve("preview.png"), "png");
        SyncEngine pc = new SyncEngine(cloud, WorldPaths.of(save, userData.resolve("CloudSaveSync")),
                new DeviceInfo("pc-id", "Gaming PC"), clock, QUIET);
        pc.marker().mark("session");
        pc.uploadSave(SyncEngine.REASON_EXIT, true, ProgressListener.NONE);
    }

    private static List<String> names(List<WorldImporter.CloudWorld> worlds) {
        return worlds.stream().map(WorldImporter.CloudWorld::worldKey).toList();
    }

    @Test
    void importsMissingWorldsAsCompleteSavesThatAreAlreadyInSync() throws IOException {
        List<WorldImporter.CloudWorld> missing = deck.missing(deck.cloudWorlds());
        assertEquals(List.of("Alpha", "Beta World"), names(missing));

        for (WorldImporter.CloudWorld w : missing) {
            deck.importWorld(w, ProgressListener.NONE, 0);
        }

        Path beta = deckSaves.resolve("Beta World");
        assertEquals("Beta World content", Files.readString(beta.resolve("universe/players/p.json")));
        assertTrue(Files.readString(beta.resolve("config.json")).contains("CloudSaveSync"), "mod stays enabled");
        assertTrue(Files.exists(beta.resolve("preview.png")), "world list thumbnail comes along");

        SyncEngine onDeck = new SyncEngine(cloud, WorldPaths.of(beta, deckSaves.getParent().resolve("CloudSaveSync")),
                new DeviceInfo("deck-id", "Steam Deck"), clock, QUIET);
        assertEquals(StartPlan.Action.UP_TO_DATE, onDeck.planStart(onDeck.fetch()).action(),
                "opening an imported world must not ask about a conflict");
        assertFalse(onDeck.marker().isSet());
        assertTrue(deck.missing(deck.cloudWorlds()).isEmpty());
        try (Stream<Path> s = Files.list(deckSaves)) {
            assertEquals(2, s.count(), "no staging folders left in Saves");
        }
    }

    @Test
    void neverTouchesAnExistingFolderWithTheSameName() throws IOException {
        Path local = Files.createDirectories(deckSaves.resolve("Alpha/universe"));
        Files.writeString(local.resolve("mine.txt"), "local world");

        assertEquals(List.of("Beta World"), names(deck.missing(deck.cloudWorlds())));
        WorldImporter.CloudWorld alpha = deck.cloudWorlds().getFirst();
        assertThrows(IOException.class, () -> deck.importWorld(alpha, ProgressListener.NONE, 0));
        assertEquals("local world", Files.readString(local.resolve("mine.txt")));
    }

    @Test
    void aWorldDeletedOnThisDeviceIsNotDownloadedAgain() throws IOException {
        WorldImporter.CloudWorld alpha = deck.cloudWorlds().getFirst();
        Path imported = deck.importWorld(alpha, ProgressListener.NONE, 0);
        dev.cloudsavesync.core.local.AtomicFiles.deleteRecursively(imported);

        assertEquals(List.of("Beta World"), names(deck.missing(deck.cloudWorlds())));
    }

    @Test
    void worldsAreOptInPerAccount() throws IOException {
        Path userData = tmp.resolve("pc/UserData");
        WorldPaths fresh = WorldPaths.of(Files.createDirectories(userData.resolve("Saves/Brand New/universe")).getParent(),
                userData.resolve("CloudSaveSync"));
        SyncEngine newWorld = new SyncEngine(cloud, fresh, new DeviceInfo("pc-id", "Gaming PC"), clock, QUIET);
        assertFalse(newWorld.isSyncEnabled("alice"), "a new world is not synced until the player enables it");
        newWorld.setSyncEnabled(true, "alice");
        assertTrue(newWorld.isSyncEnabled("alice"));
        assertFalse(newWorld.isSyncEnabled("bob"), "signing in with another account doesn't start syncing it");
        assertFalse(newWorld.isSyncEnabled(null), "unknown account: not synced");
        newWorld.setSyncEnabled(false, "alice");
        assertFalse(newWorld.isSyncEnabled("alice"));

        SyncEngine syncedBeforeAccounts = new SyncEngine(cloud, WorldPaths.of(userData.resolve("Saves/Alpha"),
                userData.resolve("CloudSaveSync")), new DeviceInfo("pc-id", "Gaming PC"), clock, QUIET);
        assertFalse(syncedBeforeAccounts.isSyncEnabled(InMemoryStorageProvider.ACCOUNT.id()),
                "a world synced before worlds were tied to accounts needs /cloudsync enable once");

        Path imported = deck.importWorld(deck.cloudWorlds().getFirst(), ProgressListener.NONE, 0);
        SyncEngine onDeck = new SyncEngine(cloud, WorldPaths.of(imported, deckSaves.getParent().resolve("CloudSaveSync")),
                new DeviceInfo("deck-id", "Steam Deck"), clock, QUIET);
        assertTrue(onDeck.isSyncEnabled(InMemoryStorageProvider.ACCOUNT.id()),
                "a downloaded world syncs with the account it came from");
    }

    private SyncEngine pcEngine(String world) throws IOException {
        Path userData = tmp.resolve("pc/UserData");
        Path save = Files.createDirectories(userData.resolve("Saves").resolve(world).resolve("universe")).getParent();
        return new SyncEngine(cloud, WorldPaths.of(save, userData.resolve("CloudSaveSync")),
                new DeviceInfo("pc-id", "Gaming PC"), clock, QUIET);
    }

    @Test
    void aNewWorldWithAnOldWorldsNameDoesNotInheritItsSync() throws IOException {
        SyncEngine old = pcEngine("Reused Name");
        assertFalse(old.claimWorld(), "first time: nothing to discard");
        old.setSyncEnabled(true, "alice");
        old.recordSynced(4, "snap-4", "alice");
        old.marker().mark("session");
        assertFalse(old.claimWorld(), "the same world keeps its state");
        assertTrue(old.isSyncEnabled("alice"));

        dev.cloudsavesync.core.local.AtomicFiles.deleteRecursively(old.paths().saveDir());
        SyncEngine fresh = pcEngine("Reused Name");

        assertTrue(fresh.claimWorld());
        assertFalse(fresh.isSyncEnabled("alice"), "a new world never starts syncing on its own");
        assertEquals(null, fresh.localState().lastSyncedRevision(), "it must not be compared with the old world's cloud");
        assertFalse(fresh.marker().isSet());
        assertFalse(fresh.claimWorld(), "from now on it is the same world");
    }

    @Test
    void worldsSyncedBeforeIdsExistedKeepTheirSync() throws IOException {
        SyncEngine existing = pcEngine("Old World");
        existing.setSyncEnabled(true, "alice");
        Path toggle = existing.paths().worldStateDir().resolve("sync.json");
        // Enabled after the save folder was created: the same world.
        Files.setLastModifiedTime(toggle, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(3600)));
        assertFalse(existing.claimWorld());
        assertTrue(existing.isSyncEnabled("alice"));

        SyncEngine recreated = pcEngine("Recreated World");
        recreated.setSyncEnabled(true, "alice");
        // Enabled long before this save folder was created: a deleted world's leftovers.
        Files.setLastModifiedTime(recreated.paths().worldStateDir().resolve("sync.json"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2001-01-01T00:00:00Z")));
        assertTrue(recreated.claimWorld());
        assertFalse(recreated.isSyncEnabled("alice"));
    }

    @Test
    void theWorldIdIsNeverUploaded() throws IOException {
        SyncEngine pc = pcEngine("Alpha");
        pc.claimWorld();
        assertTrue(Files.exists(pc.paths().worldIdFile()));
        Path zip = tmp.resolve("alpha.zip");
        dev.cloudsavesync.core.archive.SnapshotArchiver.createSnapshot(pc.paths().saveDir(), zip, 0);
        try (var z = new java.util.zip.ZipFile(zip.toFile())) {
            assertTrue(z.stream().noneMatch(e -> e.getName().endsWith("world-id")), "snapshots leave the id out");
            assertTrue(z.stream().anyMatch(e -> e.getName().startsWith("universe/")));
        }
        Path imported = deck.importWorld(deck.cloudWorlds().getFirst(), ProgressListener.NONE, 0);
        String deckId = Files.readString(WorldPaths.of(imported, deckSaves.getParent().resolve("CloudSaveSync")).worldIdFile());
        assertFalse(deckId.equals(Files.readString(pc.paths().worldIdFile())), "each device's copy has its own id");
    }

    @Test
    void enablingForAnotherAccountTreatsTheWorldAsNeverSynced() throws IOException {
        Path imported = deck.importWorld(deck.cloudWorlds().getFirst(), ProgressListener.NONE, 0);
        SyncEngine onDeck = new SyncEngine(cloud, WorldPaths.of(imported, deckSaves.getParent().resolve("CloudSaveSync")),
                new DeviceInfo("deck-id", "Steam Deck"), clock, QUIET);
        assertTrue(onDeck.localState().lastSyncedRevision() != null);

        onDeck.setSyncEnabled(true, "someone-else");

        assertTrue(onDeck.localState().lastSyncedRevision() == null,
                "revisions from the previous account's Drive must not be compared with the new account's");
        onDeck.setSyncEnabled(true, "someone-else");
        assertTrue(onDeck.isSyncEnabled("someone-else"));
    }

    @Test
    void anExplicitDownloadBringsBackAWorldDeletedHere() throws IOException {
        WorldImporter.CloudWorld alpha = deck.cloudWorlds().getFirst();
        dev.cloudsavesync.core.local.AtomicFiles.deleteRecursively(deck.importWorld(alpha, ProgressListener.NONE, 0));
        assertFalse(names(deck.missing(deck.cloudWorlds())).contains("Alpha"), "not offered automatically");

        Path again = deck.importWorld(alpha, ProgressListener.NONE, 0);
        assertTrue(Files.exists(again.resolve("universe/players/p.json")));
    }

    @Test
    void unsafeFolderNamesAreRejected() {
        for (String bad : List.of("", " ", ".", "..", "a/b", "a\\b", "a:b", "what?", "CON", "nul.txt", "trailing.",
                "trailing ", "tab\there")) {
            assertFalse(WorldImporter.isSafeFolderName(bad), bad);
        }
        for (String ok : List.of("first wordl", "My World (2)", "Zażółć", "a.b", "Beta_World")) {
            assertTrue(WorldImporter.isSafeFolderName(ok), ok);
        }
    }
}
