package dev.cloudsavesync.core.local;

import dev.cloudsavesync.core.json.Json;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A stable random id for this device plus a human-readable name for messages. */
public record DeviceInfo(String deviceId, String deviceName) {

    /** Loads {@code stateRoot/device.json}, creating it on first use. */
    public static DeviceInfo loadOrCreate(Path stateRoot) throws IOException {
        Path file = stateRoot.resolve("device.json");
        if (Files.exists(file)) {
            Map<String, Object> m = Json.parseObject(AtomicFiles.readString(file));
            return new DeviceInfo(Json.string(m, "deviceId"), Json.string(m, "deviceName"));
        }
        DeviceInfo created = new DeviceInfo(UUID.randomUUID().toString(), detectName());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", created.deviceId());
        m.put("deviceName", created.deviceName());
        AtomicFiles.writeString(file, Json.write(m));
        return created;
    }

    static String detectName() {
        String host = System.getenv("COMPUTERNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("HOSTNAME");
        }
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (IOException | RuntimeException e) {
                host = null;
            }
        }
        if (isSteamDeck()) {
            return host == null || host.isBlank() || host.equalsIgnoreCase("steamdeck")
                    ? "Steam Deck" : "Steam Deck (" + host + ")";
        }
        return host == null || host.isBlank() ? System.getProperty("os.name", "Unknown device") : host;
    }

    public static boolean isSteamDeck() {
        if ("1".equals(System.getenv("SteamDeck"))) {
            return true;
        }
        try {
            Path osRelease = Path.of("/etc/os-release");
            return Files.isRegularFile(osRelease)
                    && Files.readAllLines(osRelease).stream().anyMatch(l -> l.trim().equals("ID=steamos"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
