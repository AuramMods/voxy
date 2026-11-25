package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicReference;

/** The integrated client's read-only view of committed terrain, with its own section cache. */
public final class LocalLodTerrain implements AutoCloseable {
    private final ResourceKey<Level> dimension;
    private final WorldEngine world;
    private final AtomicReference<LocalLodTerrain> subscription;
    private boolean active = true;

    LocalLodTerrain(ResourceKey<Level> dimension, LodSectionStorage storage, Mapper mapper,
                    AtomicReference<LocalLodTerrain> subscription) {
        this.dimension = dimension;
        this.world = new WorldEngine(storage, null, mapper);
        this.subscription = subscription;
    }

    ResourceKey<Level> dimension() {
        return this.dimension;
    }

    public WorldEngine world() {
        return this.world;
    }

    synchronized void refresh(long[] changed) {
        if (!this.active) return;
        try {
            for (long key : changed) {
                var section = this.world.acquire(key);
                try {
                    synchronized (section) {
                        if (this.world.storage.loadSection(section) == 0) {
                            this.world.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS
                                    | WorldEngine.UPDATE_TYPE_DONT_SAVE, WorldEngine.ALL_MESH_NEIGHBORS);
                        }
                    }
                } finally {
                    section.release();
                }
            }
        } catch (RuntimeException failure) {
            Logger.warn("Could not refresh local Voxy terrain for " + this.dimension.location(), failure);
        }
    }

    public synchronized void stopUpdates() {
        this.subscription.compareAndSet(this, null);
        this.active = false;
    }

    @Override
    public void close() {
        this.stopUpdates();
        this.world.free();
    }
}
