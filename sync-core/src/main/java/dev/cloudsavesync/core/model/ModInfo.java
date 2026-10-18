package dev.cloudsavesync.core.model;

import dev.cloudsavesync.core.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A mod a world uses, recorded with each snapshot so another device can install the same file.
 *
 * @param id            {@code Group:Name} from the mod's manifest, as in the world's config.json
 * @param version       the mod's version, for messages
 * @param fileName      the jar's file name in the Mods folder
 * @param sha1Hex       checksum of the jar, verified after downloading it
 * @param installRecord the game's mod browser entry for this file (from {@code Mods/.installed.json}),
 *                      or null for a mod installed by hand. Holds the CurseForge project and file.
 */
public record ModInfo(String id, String version, String fileName, String sha1Hex, long sizeBytes,
                      Map<String, Object> installRecord) {

    public static final String CURSEFORGE = "curseforge:";

    /** The mod browser's key for this mod, e.g. {@code curseforge:1423494}, or null. */
    public String installKey() {
        return installRecord == null ? null : Json.optString(installRecord, "key");
    }

    /** CurseForge file id, or -1 if this mod didn't come from the mod browser. */
    public long curseForgeFileId() {
        String f = installRecord == null ? null : Json.optString(installRecord, "fileId");
        if (f == null || !f.startsWith(CURSEFORGE)) {
            return -1;
        }
        try {
            return Long.parseLong(f.substring(CURSEFORGE.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** True if another device can download exactly this file. */
    public boolean downloadable() {
        return curseForgeFileId() > 0 && sha1Hex != null && fileName != null;
    }

    /** "BetterMap 1.3.9" */
    public String displayName() {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return version == null || version.isBlank() ? name : name + " " + version;
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("version", version);
        m.put("fileName", fileName);
        m.put("sha1", sha1Hex);
        m.put("sizeBytes", sizeBytes);
        if (installRecord != null) {
            m.put("installRecord", installRecord);
        }
        return m;
    }

    static ModInfo fromJson(Map<String, Object> m) {
        Long size = Json.optNumber(m, "sizeBytes");
        return new ModInfo(Json.string(m, "id"), Json.optString(m, "version"), Json.optString(m, "fileName"),
                Json.optString(m, "sha1"), size == null ? 0 : size, Json.optObject(m, "installRecord"));
    }
}
