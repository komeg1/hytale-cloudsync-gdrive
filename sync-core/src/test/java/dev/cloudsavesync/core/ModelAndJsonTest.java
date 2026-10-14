package dev.cloudsavesync.core;

import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.json.JsonException;
import dev.cloudsavesync.core.local.LocalState;
import dev.cloudsavesync.core.model.CloudLayout;
import dev.cloudsavesync.core.model.SessionLock;
import dev.cloudsavesync.core.model.SnapshotInfo;
import dev.cloudsavesync.core.model.WorldManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelAndJsonTest {

    private static final Instant T = Instant.parse("2026-10-14T12:00:00Z");

    @TempDir
    Path tmp;

    @Test
    void jsonRoundTripsNestedValuesAndEscapes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "quote \" backslash \\ newline \n tab \t unicode \u0001 ż");
        m.put("int", 42L);
        m.put("big", Long.MAX_VALUE);
        m.put("neg", -7L);
        m.put("double", 1.5);
        m.put("bool", true);
        m.put("nothing", null);
        m.put("list", Arrays.asList(1L, "two", null, List.of()));
        m.put("empty", Map.of());

        assertEquals(m, Json.parse(Json.write(m)));
    }

    @Test
    void jsonRejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"a\":}", "[1,]", "{\"a\":1}x", "\"unterminated", "tru", "{a:1}")) {
            assertThrows(JsonException.class, () -> Json.parse(bad), bad);
        }
    }

    private static SnapshotInfo snap(String id, int minute, boolean pinned) {
        return new SnapshotInfo(id, "f-" + id, T.plusSeconds(60L * minute), "dev", "Dev", 10, "md5", "sha", 0,
                pinned, "exit");
    }

    @Test
    void revisionOnlyChangesWithTheHead() {
        WorldManifest m = WorldManifest.empty("w").withHead(snap("a", 1, false));
        assertEquals(1, m.revision());
        m = m.withSnapshot(snap("conflict", 2, true));
        assertEquals(1, m.revision());
        assertEquals("a", m.headSnapshotId());
        m = m.withHead(snap("b", 3, false));
        assertEquals(2, m.revision());
    }

    @Test
    void pruneKeepsHeadNewestFiveUnpinnedAndPinnedSeparately() {
        WorldManifest m = WorldManifest.empty("w");
        for (int i = 0; i < 8; i++) {
            m = m.withHead(snap("u" + i, i, false));
        }
        for (int i = 0; i < 7; i++) {
            m = m.withSnapshot(snap("p" + i, 100 + i, true));
        }
        WorldManifest.Pruned pruned = m.prune(5, 5);

        List<String> kept = pruned.manifest().snapshots().stream().map(SnapshotInfo::id).toList();
        assertEquals(List.of("u3", "u4", "u5", "u6", "u7", "p2", "p3", "p4", "p5", "p6"), kept);
        assertEquals(5, pruned.removed().size());
        assertEquals("u7", pruned.manifest().headSnapshotId());
    }

    @Test
    void pruneNeverRemovesAnOldHead() {
        // The head can be older than other snapshots (e.g. after keep-local pinned a newer one).
        WorldManifest m = WorldManifest.empty("w").withHead(snap("old-head", 0, false));
        for (int i = 1; i <= 6; i++) {
            m = m.withSnapshot(snap("x" + i, i, false));
        }
        WorldManifest pruned = m.prune(5, 5).manifest();
        assertTrue(pruned.head().isPresent());
        assertEquals(5, pruned.snapshots().size());
    }

    @Test
    void manifestJsonRoundTripAndFutureFormatRejected() {
        WorldManifest m = WorldManifest.empty("My World").withHead(snap("a", 1, false))
                .withSnapshot(snap("b", 2, true));
        assertEquals(m, WorldManifest.fromJson(m.toJson()));

        String future = m.toJson().replace("\"formatVersion\": 1", "\"formatVersion\": 99");
        assertThrows(IllegalStateException.class, () -> WorldManifest.fromJson(future));

        assertEquals(WorldManifest.DEFAULT_KEEP, m.keep(), "manifests without the field keep the default");
        WorldManifest kept = m.withKeep(3);
        assertEquals(3, WorldManifest.fromJson(kept.toJson()).keep());
        assertEquals(WorldManifest.MIN_KEEP, m.withKeep(0).keep());
    }

    @Test
    void sessionLockRoundTripAndFreshness() {
        SessionLock l = new SessionLock("d", "Deck", "s", T, T);
        assertEquals(l, SessionLock.fromJson(l.toJson()));
        assertTrue(l.isFresh(T.plus(SessionLock.STALE_AFTER).minusSeconds(1)));
        assertFalse(l.isFresh(T.plus(SessionLock.STALE_AFTER)));
    }

    @Test
    void localStateRoundTrip() throws IOException {
        LocalState s = LocalState.NEVER_SYNCED.synced(3, "s3", T)
                .withConflict(new LocalState.Conflict(4, "s4", "PC", T, T))
                .withUseCloud(new LocalState.CloudChoice(4, "s4"))
                .withPendingRestore(new LocalState.Pending(4, "s4", "s4.zip", "sha", "PC", T));
        Path file = tmp.resolve("state.json");
        s.save(file);
        assertEquals(s, LocalState.load(file));
        assertEquals(LocalState.NEVER_SYNCED, LocalState.load(tmp.resolve("missing.json")));
    }

    @Test
    void encodedWorldKeysAreSafeAndDistinct() {
        assertEquals("My+World", CloudLayout.encode("My World"));
        assertNotEquals(CloudLayout.encode("a_2B"), CloudLayout.encode("a+"));
        String encoded = CloudLayout.encode("../evil/\\:*?\"<>|");
        assertTrue(encoded.matches("[A-Za-z0-9._+\\-]+"), encoded);
    }
}
