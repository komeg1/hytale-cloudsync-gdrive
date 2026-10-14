package dev.cloudsavesync.plugin;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkSaveEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Records when chunks were last saved. Periodic uploads wait for a quiet moment between the
 * server's autosaves (every 10 s) so the zip is less likely to catch a region file mid-write.
 * Runs on the world thread: it only stores a timestamp.
 */
final class SaveActivitySystem extends EntityEventSystem<ChunkStore, ChunkSaveEvent> {

    private final AtomicLong lastSaveNanos = new AtomicLong(System.nanoTime());

    SaveActivitySystem() {
        super(ChunkSaveEvent.class);
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return WorldChunk.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<ChunkStore> archetypeChunk, Store<ChunkStore> store,
                       CommandBuffer<ChunkStore> commandBuffer, ChunkSaveEvent event) {
        lastSaveNanos.set(System.nanoTime());
    }

    long millisSinceLastSave() {
        return (System.nanoTime() - lastSaveNanos.get()) / 1_000_000;
    }
}
