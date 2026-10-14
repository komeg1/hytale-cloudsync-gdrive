package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * "This device has world changes the cloud doesn't have yet."
 *
 * <p>Set whenever a session starts (and again at shutdown), cleared only after a confirmed upload of
 * a snapshot taken while the world was closed. Each {@link #mark} gets a new generation number;
 * {@link #clearIf} only clears the generation that was read before the snapshot was taken, so if a
 * new session started while an upload was running the marker survives.
 *
 * <p>Clearing keeps the file (with {@code "set": false}) so generation numbers are never reused.
 */
public final class UnsyncedMarker {

    private final Path file;
    private final Clock clock;

    public UnsyncedMarker(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    private record Stored(boolean set, long generation) {
    }

    private Stored read() throws IOException {
        if (!Files.exists(file)) {
            return new Stored(false, 0);
        }
        try {
            Map<String, Object> m = Json.parseObject(AtomicFiles.readString(file));
            return new Stored(Json.optBoolean(m, "set", true), Json.number(m, "generation"));
        } catch (RuntimeException corrupt) {
            // A damaged marker still means "unsynced": never treat it as clean.
            return new Stored(true, Long.MAX_VALUE);
        }
    }

    private void write(boolean set, long generation, String reason) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("set", set);
        m.put("generation", generation);
        m.put("reason", reason);
        m.put("at", clock.instant().toString());
        AtomicFiles.writeString(file, Json.write(m));
    }

    public synchronized long mark(String reason) throws IOException {
        Stored s = read();
        long previous = s.generation() == Long.MAX_VALUE ? 0 : s.generation();
        long generation = Math.max(previous + 1, clock.millis());
        write(true, generation, reason);
        return generation;
    }

    /** The current generation if the marker is set. */
    public synchronized OptionalLong generation() throws IOException {
        Stored s = read();
        return s.set() ? OptionalLong.of(s.generation()) : OptionalLong.empty();
    }

    public boolean isSet() throws IOException {
        return generation().isPresent();
    }

    /** Clears the marker only if it still holds {@code expectedGeneration}. */
    public synchronized boolean clearIf(long expectedGeneration) throws IOException {
        Stored s = read();
        if (s.set() && s.generation() == expectedGeneration) {
            write(false, s.generation(), "uploaded");
            return true;
        }
        return false;
    }

    /** Unconditional clear, used after the local world was replaced by a cloud version. */
    public synchronized void clear() throws IOException {
        Stored s = read();
        write(false, s.generation() == Long.MAX_VALUE ? clock.millis() : s.generation(), "restored from cloud");
    }
}
