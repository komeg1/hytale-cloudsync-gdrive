package dev.cloudsavesync.core.mods;

import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.local.AtomicFiles;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.ModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads which mods a world uses: the mods enabled in the world's {@code config.json} ({@code "Mods":
 * {"Group:Name": {"Enabled": true}}}), matched to jars in the Mods folder by the {@code manifest.json}
 * inside each jar, plus the game's mod browser records ({@code Mods/.installed.json}) that say which
 * CurseForge file each jar is.
 */
public final class ModScanner {

    /** This mod itself: every device that syncs has it already. */
    public static final String OWN_ID = "CloudSaveSync:CloudSaveSync";
    static final String INSTALL_RECORDS = ".installed.json";

    /** A mod jar in the Mods folder. */
    public record LocalMod(String id, String version, Path jar) {
    }

    private ModScanner() {
    }

    /** Mods the world uses that another device might be missing: everything but the game's own and this mod. */
    public static boolean isSyncable(String id) {
        return id != null && id.contains(":") && !id.startsWith("Hytale:") && !id.equals(OWN_ID);
    }

    /** The mods enabled in a world's config.json, in file order. Empty if there's no config. */
    public static Set<String> enabledInWorld(Path saveDir) {
        Set<String> ids = new LinkedHashSet<>();
        Path config = saveDir.resolve("config.json");
        try {
            Map<String, Object> mods = Json.optObject(Json.parseObject(AtomicFiles.readString(config)), "Mods");
            if (mods != null) {
                for (Map.Entry<String, Object> e : mods.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("Enabled"))) {
                        ids.add(e.getKey());
                    }
                }
            }
        } catch (IOException | RuntimeException missingOrDamaged) {
            // no config yet (a world that never started), or one the game will rewrite anyway
        }
        return ids;
    }

    /** Mod jars in {@code modsDir} by {@code Group:Name}. Jars without a readable manifest are skipped. */
    public static Map<String, LocalMod> installed(Path modsDir) {
        Map<String, LocalMod> out = new TreeMap<>();
        if (!Files.isDirectory(modsDir)) {
            return out;
        }
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(modsDir)) {
            for (Path jar : dir) {
                String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!Files.isRegularFile(jar) || !(name.endsWith(".jar") || name.endsWith(".zip"))) {
                    continue;
                }
                LocalMod m = readManifest(jar);
                if (m != null) {
                    out.putIfAbsent(m.id(), m);
                }
            }
        } catch (IOException e) {
            // an unreadable Mods folder means no known mods
        }
        return out;
    }

    static LocalMod readManifest(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) {
                return null;
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, Object> m = Json.parseObject(text);
            String group = Json.optString(m, "Group");
            String name = Json.optString(m, "Name");
            if (group == null || name == null) {
                return null;
            }
            return new LocalMod(group + ":" + name, Json.optString(m, "Version"), jar);
        } catch (IOException | RuntimeException notAMod) {
            return null;
        }
    }

    /**
     * The mod browser's records by jar file name, each with its key (e.g. {@code curseforge:1423494})
     * added as {@code "key"}. Empty if there are none or the file can't be read.
     */
    public static Map<String, Map<String, Object>> installRecords(Path modsDir) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path f = modsDir.resolve(INSTALL_RECORDS);
        if (!Files.exists(f)) {
            return out;
        }
        try {
            Map<String, Object> mods = Json.optObject(Json.parseObject(AtomicFiles.readString(f)), "mods");
            if (mods != null) {
                for (Map.Entry<String, Object> e : mods.entrySet()) {
                    if (e.getValue() instanceof Map<?, ?> raw) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> record = new LinkedHashMap<>((Map<String, Object>) raw);
                        record.put("key", e.getKey());
                        String file = Json.optString(record, "relativePath");
                        out.put(file != null ? file : Json.optString(record, "fileName"), record);
                    }
                }
            }
        } catch (IOException | RuntimeException damaged) {
            // treat every mod as installed by hand
        }
        return out;
    }

    /** The mods this world uses, as installed on this device: what an upload records. */
    public static List<ModInfo> forWorld(WorldPaths paths) throws IOException {
        Map<String, LocalMod> installed = installed(paths.modsDir());
        Map<String, Map<String, Object>> records = installRecords(paths.modsDir());
        List<ModInfo> out = new ArrayList<>();
        for (String id : enabledInWorld(paths.saveDir())) {
            LocalMod m = installed.get(id);
            if (!isSyncable(id) || m == null) {
                continue;
            }
            String fileName = m.jar().getFileName().toString();
            out.add(new ModInfo(id, m.version(), fileName, sha1(m.jar()), Files.size(m.jar()), records.get(fileName)));
        }
        return out;
    }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
