package dev.cloudsavesync.core.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds Hytale's UserData folder.
 *
 * <p>The plugin normally doesn't need the OS defaults: the server runs with the save folder as its
 * working directory ({@code .../UserData/Saves/<world>}), so UserData is two levels up, whatever
 * the OS or install type (Flatpak included). The per-OS locations are the fallback for when that
 * layout isn't recognised.
 */
public final class UserDataLocator {

    public static final String STATE_FOLDER_NAME = "CloudSaveSync";

    private UserDataLocator() {
    }

    /** UserData from a save folder path, if it has the {@code UserData/Saves/<world>} shape. */
    public static Optional<Path> fromSaveDir(Path saveDir) {
        Path abs = saveDir.toAbsolutePath().normalize();
        Path saves = abs.getParent();
        if (saves == null || saves.getFileName() == null
                || !saves.getFileName().toString().equalsIgnoreCase("Saves")) {
            return Optional.empty();
        }
        return Optional.ofNullable(saves.getParent());
    }

    /** The state root for a save: next to Saves when recognisable, else under the OS default. */
    public static Path stateRootFor(Path saveDir, String osName, Map<String, String> env, Path home) {
        return fromSaveDir(saveDir)
                .or(() -> osDefault(osName, env, home))
                .orElse(home.resolve(".cloudsavesync").resolve("UserData"))
                .resolve(STATE_FOLDER_NAME);
    }

    public static Path stateRootFor(Path saveDir) {
        return stateRootFor(saveDir, System.getProperty("os.name", ""), System.getenv(),
                Path.of(System.getProperty("user.home")));
    }

    /** The first existing candidate, or the primary default if none exists yet. */
    public static Optional<Path> osDefault(String osName, Map<String, String> env, Path home) {
        List<Path> candidates = candidates(osName, env, home);
        return candidates.stream().filter(Files::isDirectory).findFirst()
                .or(() -> candidates.stream().findFirst());
    }

    static List<Path> candidates(String osName, Map<String, String> env, Path home) {
        String os = osName.toLowerCase(Locale.ROOT);
        List<Path> out = new ArrayList<>();
        if (os.startsWith("windows")) {
            String appData = env.get("APPDATA");
            Path base = appData != null ? Path.of(appData) : home.resolve("AppData").resolve("Roaming");
            out.add(base.resolve("Hytale").resolve("UserData"));
        } else if (os.contains("mac") || os.contains("darwin")) {
            out.add(home.resolve("Library").resolve("Application Support").resolve("Hytale").resolve("UserData"));
        } else {
            String xdgData = env.get("XDG_DATA_HOME");
            if (xdgData != null && !xdgData.isBlank()) {
                // Inside a Flatpak sandbox this already points at ~/.var/app/<id>/data.
                out.add(Path.of(xdgData).resolve("Hytale").resolve("UserData"));
            }
            out.add(home.resolve(".local").resolve("share").resolve("Hytale").resolve("UserData"));
            out.addAll(flatpakCandidates(home));
        }
        return out;
    }

    /** {@code ~/.var/app/<app id>/data/Hytale/UserData} for any installed Flatpak app id. */
    static List<Path> flatpakCandidates(Path home) {
        Path apps = home.resolve(".var").resolve("app");
        if (!Files.isDirectory(apps)) {
            return List.of();
        }
        try (Stream<Path> ids = Files.list(apps)) {
            return ids.map(id -> id.resolve("data").resolve("Hytale").resolve("UserData"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
