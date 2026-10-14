package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether a world is synced on this device, and for which cloud account. Worlds are opt-in
 * ({@code /cloudsync enable}) and the choice belongs to the account that was signed in: signing in
 * with another account never starts syncing a world on its own.
 */
public final class SyncToggle {

    /** @param accountId null in files written before worlds were tied to an account */
    public record State(boolean enabled, String accountId) {
    }

    private SyncToggle() {
    }

    static Path file(WorldPaths paths) {
        return paths.worldStateDir().resolve("sync.json");
    }

    /** Null if the player never turned sync on or off for this world on this device. */
    public static State read(WorldPaths paths) throws IOException {
        Path f = file(paths);
        if (!Files.exists(f)) {
            return null;
        }
        try {
            Map<String, Object> m = Json.parseObject(AtomicFiles.readString(f));
            return new State(Json.optBoolean(m, "enabled", false), Json.optString(m, "accountId"));
        } catch (RuntimeException corrupt) {
            return null;
        }
    }

    /** True only if sync was turned on for exactly this account. */
    public static boolean isEnabled(WorldPaths paths, String accountId) throws IOException {
        State s = read(paths);
        return s != null && s.enabled() && accountId != null && accountId.equals(s.accountId());
    }

    public static void set(WorldPaths paths, boolean enabled, String accountId) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("accountId", accountId);
        AtomicFiles.writeString(file(paths), Json.write(m));
    }
}
