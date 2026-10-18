package dev.cloudsavesync.core.mods;

import com.sun.net.httpserver.HttpServer;
import dev.cloudsavesync.core.json.Json;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.ModInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModsTest {

    @TempDir
    Path tmp;

    private Path userData;
    private WorldPaths world;
    private HttpServer server;
    private final Map<String, byte[]> served = new HashMap<>();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws IOException {
        userData = tmp.resolve("UserData");
        Path save = Files.createDirectories(userData.resolve("Saves/My World/universe")).getParent();
        world = WorldPaths.of(save, userData.resolve("CloudSaveSync"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = served.get(ex.getRequestURI().getRawPath());
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static byte[] modJar(String group, String name, String version) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"Group\":\"" + group + "\",\"Name\":\"" + name + "\",\"Version\":\"" + version + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private void writeConfig(String... enabled) throws IOException {
        StringBuilder mods = new StringBuilder();
        for (String id : enabled) {
            mods.append(mods.isEmpty() ? "" : ",").append("\"").append(id).append("\":{\"Enabled\":true}");
        }
        Files.writeString(world.saveDir().resolve("config.json"),
                "{\"Version\":4,\"Mods\":{" + mods + ",\"Off:Mod\":{\"Enabled\":false}}}");
    }

    private ModInstaller installer() {
        return new ModInstaller(HttpClient.newHttpClient(),
                List.of("http://127.0.0.1:" + server.getAddress().getPort()), clock);
    }

    @Test
    void recordsOnlyTheThirdPartyModsEnabledForTheWorld() throws IOException {
        Path mods = Files.createDirectories(world.modsDir());
        Files.write(mods.resolve("BetterMap-1.3.9.jar"), modJar("dev.ninesliced", "BetterMap", "1.3.9"));
        Files.write(mods.resolve("Manual.jar"), modJar("Me", "Manual", "2.0"));
        Files.write(mods.resolve("Unused.jar"), modJar("Other", "Unused", "1"));
        Files.write(mods.resolve("CloudSaveSync-0.3.0.jar"), modJar("CloudSaveSync", "CloudSaveSync", "0.3.0"));
        Files.writeString(mods.resolve(".installed.json"), """
                {"version":1,"mods":{"curseforge:1430352":{"fileId":"curseforge:9146451",
                 "fileName":"BetterMap-1.3.9.jar","relativePath":"BetterMap-1.3.9.jar","name":"BetterMap"}}}""");
        writeConfig("CloudSaveSync:CloudSaveSync", "dev.ninesliced:BetterMap", "Me:Manual", "Hytale:Shop", "Gone:Mod");

        assertEquals(Set.of("CloudSaveSync:CloudSaveSync", "dev.ninesliced:BetterMap", "Me:Manual", "Hytale:Shop",
                "Gone:Mod"), ModScanner.enabledInWorld(world.saveDir()));
        List<ModInfo> recorded = ModScanner.forWorld(world);

        assertEquals(List.of("dev.ninesliced:BetterMap", "Me:Manual"), recorded.stream().map(ModInfo::id).toList(),
                "not the game's own, not this mod, not disabled or missing ones");
        ModInfo map = recorded.getFirst();
        assertEquals(9146451, map.curseForgeFileId());
        assertEquals("curseforge:1430352", map.installKey());
        assertTrue(map.downloadable());
        assertFalse(recorded.get(1).downloadable(), "installed by hand: no CurseForge file to fetch");
        assertEquals("BetterMap 1.3.9", map.displayName());
    }

    @Test
    void installsTheExactFileAndTellsTheModBrowser() throws IOException {
        byte[] jar = modJar("JarHax", "EyeSpy", "2026.9.1");
        served.put("/files/8787/281/Eye%20Spy-2026.9.1.jar", jar);
        Path tmpJar = Files.write(tmp.resolve("x.jar"), jar);
        ModInfo mod = new ModInfo("JarHax:EyeSpy", "2026.9.1", "Eye Spy-2026.9.1.jar", ModScanner.sha1(tmpJar),
                jar.length, Map.of("key", "curseforge:1423494", "fileId", "curseforge:8787281", "name", "EyeSpy",
                "relativePath", "elsewhere.jar"));

        Path installed = installer().install(mod, world.modsDir());

        assertEquals(world.modsDir().resolve("Eye Spy-2026.9.1.jar"), installed);
        assertEquals("JarHax:EyeSpy", ModScanner.installed(world.modsDir()).keySet().iterator().next());
        Map<String, Object> record = Json.optObject(Json.optObject(Json.parseObject(
                Files.readString(world.modsDir().resolve(".installed.json"))), "mods"), "curseforge:1423494");
        assertEquals("Eye Spy-2026.9.1.jar", record.get("relativePath"));
        assertEquals("EyeSpy", record.get("name"));
        assertFalse(record.containsKey("key"));
        assertEquals(clock.instant().getEpochSecond(), record.get("installedAt"));

        served.clear();
        assertEquals(installed, installer().install(mod, world.modsDir()), "already there: nothing downloaded");
    }

    @Test
    void aFileThatDoesntMatchIsNeverInstalled() throws IOException {
        served.put("/files/9146/451/BetterMap.jar", modJar("Evil", "Thing", "1"));
        ModInfo mod = new ModInfo("dev.ninesliced:BetterMap", "1.3.9", "BetterMap.jar",
                "0000000000000000000000000000000000000000", 10,
                Map.of("key", "curseforge:1430352", "fileId", "curseforge:9146451"));

        assertThrows(IOException.class, () -> installer().install(mod, world.modsDir()));
        assertTrue(ModScanner.installed(world.modsDir()).isEmpty());
        try (var files = Files.list(world.modsDir())) {
            assertEquals(0, files.count(), "no partial download left behind");
        }
    }

    @Test
    void aModInstalledByHandCantBeDownloaded() {
        ModInfo mod = new ModInfo("Me:Manual", "2.0", "Manual.jar", "ab", 1, null);
        assertThrows(IOException.class, () -> installer().install(mod, world.modsDir()));
    }

    @Test
    void fileNamesCantEscapeTheModsFolder() throws IOException {
        byte[] jar = modJar("A", "B", "1");
        served.put("/files/1/1/evil.jar", jar);
        Path tmpJar = Files.write(tmp.resolve("e.jar"), jar);
        ModInfo mod = new ModInfo("A:B", "1", "../../evil.jar", ModScanner.sha1(tmpJar), jar.length,
                Map.of("key", "curseforge:1", "fileId", "curseforge:1001"));

        assertEquals(world.modsDir().resolve("evil.jar"), installer().install(mod, world.modsDir()));
    }
}
