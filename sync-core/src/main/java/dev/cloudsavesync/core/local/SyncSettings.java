package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-user settings in {@code <state root>/settings.json}, shared by all worlds on this device.
 * Changed in game with {@code /cloudsync auto} and {@code /cloudsync notify}; the file can also be
 * edited by hand.
 *
 * <p>The upload after leaving a synced world is not configurable: without it other devices would
 * never get this device's progress.
 *
 * @param autoUpload        whether to upload every {@code autoUploadMinutes} while playing
 * @param autoUploadMinutes interval between uploads during play, clamped to [{@link #MIN_MINUTES},
 *                          {@link #MAX_MINUTES}]
 * @param notifications     whether the upload after leaving a world shows desktop notifications
 */
public record SyncSettings(boolean autoUpload, int autoUploadMinutes, boolean notifications) {

    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 240;
    public static final SyncSettings DEFAULTS = new SyncSettings(true, 7, true);

    public SyncSettings {
        autoUploadMinutes = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, autoUploadMinutes));
    }

    public static Path file(Path stateRoot) {
        return stateRoot.resolve("settings.json");
    }

    /** Defaults if the file is missing; damaged or partial files fall back field by field. */
    public static SyncSettings load(Path stateRoot) {
        Path f = file(stateRoot);
        if (!Files.exists(f)) {
            return DEFAULTS;
        }
        try {
            Map<String, Object> m = Json.parseObject(AtomicFiles.readString(f));
            Long minutes = Json.optNumber(m, "autoUploadMinutes");
            return new SyncSettings(Json.optBoolean(m, "autoUpload", DEFAULTS.autoUpload()),
                    minutes == null ? DEFAULTS.autoUploadMinutes() : (int) Math.min(Integer.MAX_VALUE, minutes),
                    Json.optBoolean(m, "notifications", DEFAULTS.notifications()));
        } catch (IOException | RuntimeException e) {
            return DEFAULTS;
        }
    }

    public void save(Path stateRoot) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("autoUpload", autoUpload);
        m.put("autoUploadMinutes", autoUploadMinutes);
        m.put("notifications", notifications);
        m.put("_help", "autoUpload: back up synced worlds while playing (true/false). autoUploadMinutes: how often ("
                + MIN_MINUTES + "-" + MAX_MINUTES + "). A synced world is always uploaded when you leave it."
                + " notifications: desktop notifications while a world uploads after you leave it (true/false)."
                + " In game: /cloudsync auto on|off|<minutes>, /cloudsync notify on|off");
        AtomicFiles.writeString(file(stateRoot), Json.write(m));
    }

    public SyncSettings withAutoUpload(boolean on) {
        return new SyncSettings(on, autoUploadMinutes, notifications);
    }

    public SyncSettings withMinutes(int minutes) {
        return new SyncSettings(true, minutes, notifications);
    }

    public SyncSettings withNotifications(boolean on) {
        return new SyncSettings(autoUpload, autoUploadMinutes, on);
    }
}
