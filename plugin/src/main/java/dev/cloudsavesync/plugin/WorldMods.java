package dev.cloudsavesync.plugin;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.config.ModConfig;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import dev.cloudsavesync.core.local.WorldPaths;
import dev.cloudsavesync.core.model.ModInfo;
import dev.cloudsavesync.core.mods.ModInstaller;
import dev.cloudsavesync.core.mods.ModScanner;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Makes sure the world has the mods it was saved with, so items, blocks and data from those mods
 * aren't lost. Two steps, measured against the real server:
 * <ol>
 *   <li>{@link #prepare} in {@code setup()}: missing mods are downloaded from CurseForge and enabled
 *       for the world. Mods can't be started this early: the game's own modules most mods depend
 *       on haven't started yet.</li>
 *   <li>{@link #loadPrepared} in {@code start()}: the game's modules are running and no player has
 *       joined, so the mods are loaded into this session (their assets included) before the
 *       player's data loads.</li>
 * </ol>
 * If a mod can't be installed or loaded, the world must not open.
 */
final class WorldMods {

    /**
     * @param installed    mods downloaded now
     * @param unavailable  mods that are missing and can't be installed, with the reason
     * @param otherVersion mods this device has in another version than the world was saved with
     */
    record Prepared(List<String> installed, Map<String, String> unavailable, List<String> otherVersion) {
    }

    private final WorldPaths paths;
    private final PluginSyncLog log;
    private final BiConsumer<String, Double> status;
    private final List<ModInfo> toLoad = new ArrayList<>();

    WorldMods(WorldPaths paths, PluginSyncLog log, BiConsumer<String, Double> status) {
        this.paths = paths;
        this.log = log;
        this.status = status;
    }

    /**
     * Installs and enables the world's mods. Call in {@code setup()}.
     *
     * @param required     the mods of the cloud version this device now has
     * @param alsoDisabled true after a cloud version replaced the world here: its mods are enabled
     *                     for this world even if they weren't. False when this copy already
     *                     matched the cloud: a mod the player turned off for this world stays off.
     */
    Prepared prepare(List<ModInfo> required, boolean alsoDisabled) {
        List<String> installed = new ArrayList<>();
        Map<String, String> unavailable = new LinkedHashMap<>();
        List<String> otherVersion = new ArrayList<>();

        HytaleServerConfig config = HytaleServer.get().getConfig();
        Map<String, ModScanner.LocalMod> local = ModScanner.installed(paths.modsDir());
        ModInstaller installer = new ModInstaller(Clock.systemUTC());
        boolean configChanged = false;

        for (ModInfo mod : required) {
            if (!ModScanner.isSyncable(mod.id())) {
                continue;
            }
            PluginIdentifier pid = PluginIdentifier.fromString(mod.id());
            if (!alsoDisabled && !enabled(config, pid)) {
                continue;
            }
            ModScanner.LocalMod have = local.get(mod.id());
            if (have == null) {
                if (!mod.downloadable()) {
                    unavailable.put(mod.displayName(), "it was installed by hand, not from the mod browser");
                    continue;
                }
                status.accept("Installing " + mod.displayName() + " (a mod this world uses)...", 85.0);
                try {
                    installer.install(mod, paths.modsDir());
                    installed.add(mod.displayName());
                    log.info("installed mod " + mod.id() + " from " + mod.installKey() + " (" + mod.fileName() + ")");
                } catch (IOException e) {
                    log.warn("could not install mod " + mod.id(), e);
                    unavailable.put(mod.displayName(), e.getMessage());
                    continue;
                }
            } else if (mod.version() != null && !mod.version().equals(have.version())) {
                otherVersion.add(mod.displayName() + " (this device has " + have.version() + ")");
            }
            if (!enabled(config, pid)) {
                HytaleServerConfig.setBoot(config, pid, true);
                configChanged = true;
            }
            if (PluginManager.get().getPlugin(pid) == null) {
                toLoad.add(mod);
            }
        }

        if (configChanged) {
            try {
                HytaleServerConfig.save(config).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("could not save the world's mod settings", e);
            }
        }
        return new Prepared(installed, unavailable, otherVersion);
    }

    /**
     * Loads the mods {@link #prepare} installed or enabled. Call in {@code start()}.
     *
     * @return the mods that couldn't be loaded (empty if all are running)
     */
    List<String> loadPrepared() {
        // A mod can need another one loaded first: retry until nothing more loads.
        boolean progress = true;
        while (progress && !toLoad.isEmpty()) {
            progress = false;
            for (ModInfo mod : List.copyOf(toLoad)) {
                if (load(mod)) {
                    toLoad.remove(mod);
                    progress = true;
                }
            }
        }
        return toLoad.stream().map(ModInfo::displayName).toList();
    }

    boolean hasModsToLoad() {
        return !toLoad.isEmpty();
    }

    private boolean load(ModInfo mod) {
        PluginIdentifier pid = PluginIdentifier.fromString(mod.id());
        try {
            boolean ok = PluginManager.get().load(pid) && PluginManager.get().getPlugin(pid) != null;
            log.info((ok ? "loaded mod " : "could not load mod ") + mod.id() + " into this session");
            return ok;
        } catch (RuntimeException | LinkageError e) {
            log.warn("loading mod " + mod.id() + " failed", e);
            return false;
        }
    }

    private static boolean enabled(HytaleServerConfig config, PluginIdentifier pid) {
        ModConfig c = config.getModConfig().get(pid);
        return c != null && Boolean.TRUE.equals(c.getEnabled());
    }
}
