package dev.cloudsavesync.spike;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.PrepareUniverseEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.event.events.player.RemovedPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.StartWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 0 spike: logs when each lifecycle hook and world/player/save event fires, and probes
 * whether the save files are open or locked at setup(), after all worlds load, at the first
 * player join and at shutdown. It's read-only and never changes the save.
 */
public class SpikePlugin extends JavaPlugin {

    private final SpikeLog log;
    private final AtomicBoolean firstJoinProbed = new AtomicBoolean();
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "spike-probe");
        t.setDaemon(true);
        return t;
    });
    private ChunkSaveProbeSystem chunkSaveProbe;

    public SpikePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        log = new SpikeLog(getLogger(), getDataDirectory());
        log.log("==== session start ====", "pid=" + ProcessHandle.current().pid()
                + " java=" + System.getProperty("java.version")
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        log.log("constructor", "spikeLog=" + log.file());
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdownHookSurvivalTest, "spike-shutdown-hook"));
    }

    /**
     * Measures how long the client lets the server process live after shutdown starts. Ticks once a
     * second for {@link #HOOK_TEST_SECONDS}s: the last tick in the log is how long we survived. No
     * "jvm-shutdown-hook:begin" line at all means the hook never ran (hard kill).
     */
    private void shutdownHookSurvivalTest() {
        long begin = System.nanoTime();
        log.log("jvm-shutdown-hook:begin", "testing survival for " + HOOK_TEST_SECONDS + "s"
                + " parentAlive=" + parentAlive());
        for (int s = 1; s <= HOOK_TEST_SECONDS; s++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                log.log("jvm-shutdown-hook:interrupted", "after " + elapsedMs(begin) + "ms");
                return;
            }
            log.log("jvm-shutdown-hook:tick", s + "/" + HOOK_TEST_SECONDS + " elapsed=" + elapsedMs(begin)
                    + "ms parentAlive=" + parentAlive());
        }
        log.log("jvm-shutdown-hook:end", "survived full " + HOOK_TEST_SECONDS + "s");
    }

    private static final int HOOK_TEST_SECONDS = 30;

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Whether the launching process (the game client) is still running. */
    private static String parentAlive() {
        return ProcessHandle.current().parent()
                .map(p -> p.pid() + ":" + p.isAlive())
                .orElse("none");
    }

    @Override
    public CompletableFuture<Void> preLoad() {
        log.log("preLoad", "");
        return super.preLoad();
    }

    @Override
    protected void setup() {
        log.log("setup:begin", "");
        log.detail("user.dir=" + Path.of("").toAbsolutePath());
        log.detail("user.home=" + System.getProperty("user.home"));
        log.detail("commandLine=" + ProcessHandle.current().info().commandLine().orElse("?"));
        log.detail("dataDirectory=" + getDataDirectory().toAbsolutePath());
        log.detail("World.SAVE_INTERVAL=" + World.SAVE_INTERVAL);
        log.log("universe-state", describeUniverse());

        registerListeners();

        chunkSaveProbe = new ChunkSaveProbeSystem(log);
        try {
            getChunkStoreRegistry().registerSystem(chunkSaveProbe);
            log.log("chunk-save-probe", "registered on ChunkStore");
        } catch (RuntimeException e) {
            log.log("chunk-save-probe", "registration FAILED: " + e);
        }

        // Synchronous on purpose: this is the moment we need to measure, before any world loads.
        FileProbe.run(log, "setup", FileProbe.Mode.LOCK, probeRoots());
        log.log("setup:end", "");
    }

    @Override
    protected void start() {
        log.log("start", describeUniverse());
    }

    @Override
    protected void shutdown() {
        log.log("shutdown:begin", describeUniverse());
        if (chunkSaveProbe != null) {
            chunkSaveProbe.stop();
        }
        probeExecutor.shutdownNow();
        // Open-only and synchronous: tells Phase 1 whether files are released by the time we
        // would zip and upload them on shutdown.
        FileProbe.run(log, "shutdown", FileProbe.Mode.OPEN_ONLY, probeRoots());
        log.log("shutdown:end", "");
    }

    private void registerListeners() {
        var events = getEventRegistry();

        events.register(BootEvent.class, (BootEvent e) -> log.log("BootEvent", describeUniverse()));
        events.register(PrepareUniverseEvent.class, (PrepareUniverseEvent e) -> log.log("PrepareUniverseEvent", describeUniverse()));
        events.register(AllWorldsLoadedEvent.class, (AllWorldsLoadedEvent e) -> {
            log.log("AllWorldsLoadedEvent", describeUniverse());
            probeAsync("allWorldsLoaded");
        });
        events.register(ShutdownEvent.class, (ShutdownEvent e) -> log.log("ShutdownEvent", describeUniverse()));

        events.registerGlobal(AddWorldEvent.class, (AddWorldEvent e) -> log.log("AddWorldEvent", describeWorld(e.getWorld())));
        events.registerGlobal(StartWorldEvent.class, (StartWorldEvent e) -> log.log("StartWorldEvent", describeWorld(e.getWorld())));
        events.registerGlobal(RemoveWorldEvent.class, (RemoveWorldEvent e) -> log.log("RemoveWorldEvent",
                describeWorld(e.getWorld()) + " reason=" + e.getRemovalReason()));

        events.<PlayerConnectEvent>registerAsync(PlayerConnectEvent.class, future -> future.thenApply(e -> {
            log.log("PlayerConnectEvent", describePlayer(e.getPlayerRef()) + " " + describeWorld(e.getWorld()));
            return e;
        }));
        events.registerGlobal(AddPlayerToWorldEvent.class, (AddPlayerToWorldEvent e) ->
                log.log("AddPlayerToWorldEvent", describeWorld(e.getWorld())));
        events.registerGlobal(PlayerReadyEvent.class, (PlayerReadyEvent e) -> {
            Player p = e.getPlayer();
            String world;
            try {
                world = describeWorld(p.getWorld());
            } catch (RuntimeException ex) {
                world = "world=?";
            }
            String who;
            try {
                Ref<EntityStore> ref = e.getPlayerRef();
                who = ref.isValid()
                        ? describePlayer(ref.getStore().getComponent(ref, PlayerRef.getComponentType()))
                        : "player=invalid-ref";
            } catch (RuntimeException ex) {
                who = "player=?";
            }
            log.log("PlayerReadyEvent", who + " " + world + " readyId=" + e.getReadyId());
            if (firstJoinProbed.compareAndSet(false, true)) {
                probeAsync("firstJoin");
            }
        });
        events.registerGlobal(RemovedPlayerFromWorldEvent.class, (RemovedPlayerFromWorldEvent e) ->
                log.log("RemovedPlayerFromWorldEvent", describeWorld(e.getWorld())));
        events.register(PlayerDisconnectEvent.class, (PlayerDisconnectEvent e) -> {
            String reason;
            try {
                reason = String.valueOf(e.getDisconnectReason());
            } catch (RuntimeException ex) {
                reason = "?";
            }
            log.log("PlayerDisconnectEvent", describePlayer(e.getPlayerRef()) + " reason=" + reason);
        });

        log.log("listeners", "registered");
    }

    /** Running-world probes stay off the game thread and only open files (no locks). */
    private void probeAsync(String label) {
        log.log("probe:" + label + ":queued", "");
        try {
            probeExecutor.execute(() -> FileProbe.run(log, label, FileProbe.Mode.OPEN_ONLY, probeRoots()));
        } catch (RuntimeException e) {
            log.log("probe:" + label + ":not-run", e.toString());
        }
    }

    /**
     * The universe folder (which includes its players/ directory if player data lives there),
     * plus every world save path that isn't under it. Falls back to the working directory if the
     * universe isn't available yet.
     */
    private List<Path> probeRoots() {
        List<Path> roots = new ArrayList<>();
        Universe u = universe();
        Path universePath = null;
        if (u != null) {
            try {
                universePath = u.getPath().toAbsolutePath().normalize();
                roots.add(universePath);
                for (World w : u.getWorlds().values()) {
                    Path save = w.getSavePath().toAbsolutePath().normalize();
                    if (!save.startsWith(universePath)) {
                        roots.add(save);
                    }
                }
            } catch (RuntimeException e) {
                log.log("probe-roots", "universe lookup failed: " + e);
            }
        }
        if (universePath == null) {
            roots.add(Path.of("").toAbsolutePath());
        }
        return roots;
    }

    private Universe universe() {
        try {
            return Universe.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private String describeUniverse() {
        Universe u = universe();
        if (u == null) {
            return "universe=null";
        }
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("universePath=").append(u.getPath());
        } catch (RuntimeException e) {
            sb.append("universePath=?(").append(e.getClass().getSimpleName()).append(')');
        }
        try {
            sb.append(" worldsPath=").append(u.getWorldsPath());
        } catch (RuntimeException e) {
            sb.append(" worldsPath=?");
        }
        try {
            sb.append(" worlds=").append(u.getWorlds().keySet());
        } catch (RuntimeException e) {
            sb.append(" worlds=?");
        }
        return sb.toString();
    }

    private static String describeWorld(World w) {
        if (w == null) {
            return "world=null";
        }
        String name;
        try {
            name = w.getName();
        } catch (RuntimeException e) {
            name = "?";
        }
        String path;
        try {
            path = String.valueOf(w.getSavePath());
        } catch (RuntimeException e) {
            path = "?";
        }
        return "world=" + name + " savePath=" + path;
    }

    private static String describePlayer(PlayerRef ref) {
        if (ref == null) {
            return "player=null";
        }
        try {
            return "player=" + ref.getUsername() + " uuid=" + ref.getUuid();
        } catch (RuntimeException e) {
            return "player=?";
        }
    }
}
