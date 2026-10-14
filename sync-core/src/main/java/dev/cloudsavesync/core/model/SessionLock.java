package dev.cloudsavesync.core.model;

import dev.cloudsavesync.core.json.Json;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "This world is open on device X" marker stored next to the manifest. Advisory only: it produces a
 * warning, it never blocks anyone from playing. Refreshed by a heartbeat while the world is open.
 */
public record SessionLock(String deviceId, String deviceName, String sessionId, Instant acquiredAt,
                          Instant heartbeatAt) {

    /** A lock whose heartbeat is older than this is from a session that crashed or lost network. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    public boolean isFresh(Instant now) {
        return heartbeatAt.plus(STALE_AFTER).isAfter(now);
    }

    public SessionLock heartbeat(Instant now) {
        return new SessionLock(deviceId, deviceName, sessionId, acquiredAt, now);
    }

    public String toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", deviceId);
        m.put("deviceName", deviceName);
        m.put("sessionId", sessionId);
        m.put("acquiredAt", acquiredAt.toString());
        m.put("heartbeatAt", heartbeatAt.toString());
        return Json.write(m);
    }

    public static SessionLock fromJson(String json) {
        Map<String, Object> m = Json.parseObject(json);
        return new SessionLock(Json.string(m, "deviceId"), Json.string(m, "deviceName"),
                Json.string(m, "sessionId"), Instant.parse(Json.string(m, "acquiredAt")),
                Instant.parse(Json.string(m, "heartbeatAt")));
    }
}
