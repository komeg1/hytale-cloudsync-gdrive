package dev.cloudsavesync.core.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** How a world's files are named and tagged in the provider's flat app space. */
public final class CloudLayout {

    public static final String PROP_WORLD = "world";
    public static final String PROP_KIND = "kind";
    public static final String PROP_SNAPSHOT = "snapshot";

    public static final String KIND_MANIFEST = "manifest";
    public static final String KIND_LOCK = "lock";
    public static final String KIND_SNAPSHOT = "snapshot";

    private CloudLayout() {
    }

    public static String manifestName(String worldKey) {
        return "world." + encode(worldKey) + ".manifest.json";
    }

    public static String lockName(String worldKey) {
        return "world." + encode(worldKey) + ".lock.json";
    }

    public static String snapshotName(String worldKey, String snapshotId) {
        return "world." + encode(worldKey) + ".snap." + snapshotId + ".zip";
    }

    public static Map<String, String> props(String worldKey, String kind) {
        return Map.of(PROP_WORLD, worldKey, PROP_KIND, kind);
    }

    public static Map<String, String> snapshotProps(String worldKey, String snapshotId) {
        return Map.of(PROP_WORLD, worldKey, PROP_KIND, KIND_SNAPSHOT, PROP_SNAPSHOT, snapshotId);
    }

    /**
     * File-name-safe, reversible form of a world key (the save folder name). Used for cloud names and
     * for per-world folders on disk.
     */
    public static String encode(String worldKey) {
        // Escape '_' first so that after '%' becomes '_' every '_' still marks an escape.
        String encoded = URLEncoder.encode(worldKey, StandardCharsets.UTF_8)
                .replace("_", "%5F")
                .replace("*", "%2A")
                .replace('%', '_');
        // "." and ".." are valid encodings but would be path traversal when used as a folder name.
        return encoded.equals(".") || encoded.equals("..") ? encoded.replace(".", "_2E") : encoded;
    }
}
