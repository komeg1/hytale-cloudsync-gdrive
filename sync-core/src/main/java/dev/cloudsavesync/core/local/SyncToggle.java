package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether a world is synced on this device. Worlds are opt-in ({@code /cloudsync enable}); a world
 * that was synced before this switch existed, or that was downloaded from the cloud, counts as on.
 */
public final class SyncToggle {

    private SyncToggle() {
    }

    static Path file(WorldPaths paths) {
        return paths.worldStateDir().resolve("sync.json");
    }

    public static boolean isEnabled(WorldPaths paths) throws IOException {
        Path f = file(paths);
        if (Files.exists(f)) {
            try {
                return Json.optBoolean(Json.parseObject(AtomicFiles.readString(f)), "enabled", false);
            } catch (RuntimeException corrupt) {
                return false;
            }
        }
        return LocalState.load(paths.stateFile()).lastSyncedRevision() != null;
    }

    public static void set(WorldPaths paths, boolean enabled) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        AtomicFiles.writeString(file(paths), Json.write(m));
    }
}
