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
