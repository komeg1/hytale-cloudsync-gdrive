package dev.cloudsavesync.core.mods;

import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.local.AtomicFiles;
import dev.cloudsavesync.core.model.ModInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Installs a mod a world uses from the same CurseForge file the other device got through the game's
 * mod browser: downloads it from CurseForge's public file server, checks its SHA-1, puts it in the
 * Mods folder and adds the mod browser's record, so the game lists it like any mod it installed.
 */
public final class ModInstaller {

    /** CurseForge's public file servers, tried in order. */
    public static final List<String> CURSEFORGE_FILES = List.of("https://edge.forgecdn.net",
            "https://mediafilez.forgecdn.net");

    private final HttpClient http;
    private final List<String> fileServers;
    private final Clock clock;

    public ModInstaller(Clock clock) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10)).build(), CURSEFORGE_FILES, clock);
    }

    ModInstaller(HttpClient http, List<String> fileServers, Clock clock) {
        this.http = http;
        this.fileServers = fileServers;
        this.clock = clock;
    }

    /**
     * Downloads {@code mod} into {@code modsDir}.
     *
     * @return the installed jar
     * @throws IOException if it can't be downloaded, its checksum doesn't match, or a different file
     *                     with its name is already there
     */
    public Path install(ModInfo mod, Path modsDir) throws IOException {
        if (!mod.downloadable()) {
            throw new IOException(mod.displayName() + " wasn't installed from the mod browser, so it can't be downloaded");
        }
        String fileName = Path.of(mod.fileName()).getFileName().toString(); // never a path outside Mods
        Path target = modsDir.resolve(fileName);
        if (Files.exists(target)) {
            if (mod.sha1Hex().equalsIgnoreCase(ModScanner.sha1(target))) {
                return target;
            }
            throw new IOException("a different " + fileName + " is already in the Mods folder");
        }
        Files.createDirectories(modsDir);
        Path part = modsDir.resolve(fileName + ".download");
        try {
            download(mod, fileName, part);
            String sha1 = ModScanner.sha1(part);
            if (!sha1.equalsIgnoreCase(mod.sha1Hex())) {
                throw new IOException("the downloaded " + fileName + " doesn't match the one your other device has");
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            AtomicFiles.deleteQuietly(part);
        }
        addInstallRecord(mod, fileName, modsDir);
        return target;
    }

    private void download(ModInfo mod, String fileName, Path out) throws IOException {
        long id = mod.curseForgeFileId();
        String path;
        try {
            path = new URI(null, null, "/files/" + id / 1000 + "/" + id % 1000 + "/" + fileName, null).getRawPath();
        } catch (URISyntaxException e) {
            throw new IOException("bad file name " + fileName, e);
        }
        IOException last = null;
        for (String server : fileServers) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(server + path)).timeout(Duration.ofMinutes(5)).GET().build();
            try {
                HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(out));
                if (res.statusCode() == 200) {
                    return;
                }
                last = new IOException("CurseForge answered " + res.statusCode() + " for " + fileName);
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
        throw last;
    }

    /**
     * Adds the other device's mod browser record, so this game's mod browser knows the mod too (it
     * can update or remove it). Skipped if the record file is damaged: the jar works without it.
     */
    private void addInstallRecord(ModInfo mod, String fileName, Path modsDir) {
        String key = mod.installKey();
        if (key == null) {
            return;
        }
        Path f = modsDir.resolve(ModScanner.INSTALL_RECORDS);
        try {
            Map<String, Object> root = Files.exists(f) ? Json.parseObject(AtomicFiles.readString(f)) : new LinkedHashMap<>();
            root.putIfAbsent("version", 1L);
            Map<String, Object> mods = Json.optObject(root, "mods");
            if (mods == null) {
                mods = new LinkedHashMap<>();
                root.put("mods", mods);
            }
            Map<String, Object> record = new LinkedHashMap<>(mod.installRecord());
            record.remove("key");
            record.put("fileName", fileName);
            record.put("relativePath", fileName);
            record.put("installedAt", clock.instant().getEpochSecond());
            mods.put(key, record);
            AtomicFiles.writeString(f, Json.write(root));
        } catch (IOException | RuntimeException e) {
            // the mod is installed; the mod browser just won't know where it came from
        }
    }
}
