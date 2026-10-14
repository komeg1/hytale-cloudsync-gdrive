package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Progress file written by the upload helper, read by the plugin on the next world load: to wait
 * while the helper is still reading the save, and to report whether the helper survived.
 */
public record HelperStatus(long pid, long serverPid, Phase phase, String launchMode, Instant startedAt,
                           Instant updatedAt, String message) {

    public enum Phase {
        STARTING, WAITING_FOR_SERVER, READING_SAVE, UPLOADING, DONE, FAILED, SKIPPED;

        /** While in these phases the helper reads the save folder, so a new session must wait. */
        public boolean readsSave() {
            return this == STARTING || this == WAITING_FOR_SERVER || this == READING_SAVE;
        }

        public boolean finished() {
            return this == DONE || this == FAILED || this == SKIPPED;
        }
    }

    public HelperStatus at(Phase newPhase, Instant now, String newMessage) {
        return new HelperStatus(pid, serverPid, newPhase, launchMode, startedAt, now, newMessage);
    }

    /** True if the helper process that wrote this is still running. */
    public boolean processAlive() {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    public static Optional<HelperStatus> read(Path file) {
        try {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            Map<String, Object> m = Json.parseObject(AtomicFiles.readString(file));
            return Optional.of(new HelperStatus(Json.number(m, "pid"), Json.number(m, "serverPid"),
                    Phase.valueOf(Json.string(m, "phase")), Json.optString(m, "launchMode"),
                    Instant.parse(Json.string(m, "startedAt")), Instant.parse(Json.string(m, "updatedAt")),
                    Json.optString(m, "message")));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void write(Path file) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", pid);
        m.put("serverPid", serverPid);
        m.put("phase", phase.name());
        m.put("launchMode", launchMode);
        m.put("startedAt", startedAt.toString());
        m.put("updatedAt", updatedAt.toString());
        m.put("message", message);
        AtomicFiles.writeString(file, Json.write(m));
    }
}
