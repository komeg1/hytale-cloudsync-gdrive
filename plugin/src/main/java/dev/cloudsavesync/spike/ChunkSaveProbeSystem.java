package dev.cloudsavesync.spike;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkSaveEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Observes ChunkSaveEvent (fired per chunk, from ChunkSavingSystems.tryQueue) and reports it as
 * bursts so the log stays readable: a "begin" line on the first chunk, then a summary once the
 * world has been quiet for {@link #QUIET_MS}. The begin line is written immediately, so a hard kill
 * mid-save still leaves evidence that a save had started.
 */
final class ChunkSaveProbeSystem extends EntityEventSystem<ChunkStore, ChunkSaveEvent> {

    private static final long QUIET_MS = 1500;

    private final SpikeLog log;
    private final Map<String, Burst> bursts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService closer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "spike-chunk-burst");
        t.setDaemon(true);
        return t;
    });

    ChunkSaveProbeSystem(SpikeLog log) {
        super(ChunkSaveEvent.class);
        this.log = log;
        closer.scheduleWithFixedDelay(() -> closeBursts(false), 250, 250, TimeUnit.MILLISECONDS);
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return WorldChunk.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store,
                       CommandBuffer<ChunkStore> commandBuffer, ChunkSaveEvent event) {
        String world;
        try {
            world = store.getExternalData().getWorld().getName();
        } catch (RuntimeException e) {
            world = "?";
        }
        long now = System.currentTimeMillis();
        String w = world;
        bursts.compute(world, (k, b) -> {
            if (b == null) {
                log.log("chunk-save-burst:begin", "world=" + w + " firstChunk=" + chunkPos(event)
                        + " cancelledByOthers=" + event.isCancelled());
                b = new Burst(now);
            }
            b.count++;
            b.last = now;
            return b;
        });
    }

    /** Logs and removes bursts that have gone quiet, or all of them when {@code force} is set. */
    void closeBursts(boolean force) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Burst> e : bursts.entrySet()) {
            Burst b = e.getValue();
            if (force || now - b.last >= QUIET_MS) {
                if (bursts.remove(e.getKey(), b)) {
                    log.log("chunk-save-burst:end", "world=" + e.getKey() + " chunks=" + b.count
                            + " spanMs=" + (b.last - b.first) + (force ? " (forced at shutdown)" : ""));
                }
            }
        }
    }

    void stop() {
        closer.shutdownNow();
        closeBursts(true);
    }

    private static String chunkPos(ChunkSaveEvent event) {
        try {
            WorldChunk c = event.getChunk();
            return c.getX() + "," + c.getZ();
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private static final class Burst {
        final long first;
        long last;
        int count;

        Burst(long first) {
            this.first = first;
            this.last = first;
        }
    }
}
