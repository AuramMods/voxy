package me.cortex.voxy.server;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** The integrated client's read-only view of committed terrain, with its own section cache. */
public final class LocalLodTerrain implements AutoCloseable {
    private static final int REFRESH_BATCH_SIZE = 64;

    private final ResourceKey<Level> dimension;
    private final WorldEngine world;
    private final AtomicReference<LocalLodTerrain> subscription;
    private final Executor worker;
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
    private boolean refreshScheduled;
    private volatile boolean active = true;

    LocalLodTerrain(ResourceKey<Level> dimension, LodSectionStorage storage, Mapper mapper,
                    AtomicReference<LocalLodTerrain> subscription, Executor worker) {
        this.dimension = dimension;
        this.world = new WorldEngine(storage, null, mapper);
        this.subscription = subscription;
        this.worker = worker;
    }

    ResourceKey<Level> dimension() {
        return this.dimension;
    }

    public WorldEngine world() {
        return this.world;
    }

    void queueRefresh(long[] changed) {
        synchronized (this.pending) {
            if (!this.active) return;
            for (long key : changed) this.pending.add(key);
            if (!this.refreshScheduled && !this.pending.isEmpty()) this.scheduleRefresh();
        }
    }

    private void scheduleRefresh() {
        this.refreshScheduled = true;
        try {
            this.worker.execute(this::drainRefresh);
        } catch (RejectedExecutionException shutdown) {
            this.refreshScheduled = false;
        }
    }

    private void drainRefresh() {
        long[] changed;
        synchronized (this.pending) {
            changed = new long[Math.min(this.pending.size(), REFRESH_BATCH_SIZE)];
            for (int index = 0; index < changed.length; index++) changed[index] = this.pending.removeFirstLong();
        }
        try {
            this.refresh(changed);
        } finally {
            synchronized (this.pending) {
                if (this.active && !this.pending.isEmpty()) this.scheduleRefresh();
                else this.refreshScheduled = false;
            }
        }
    }

    private synchronized void refresh(long[] changed) {
        if (!this.active) return;
        for (long key : changed) {
            try {
                var section = this.world.acquire(key);
                try {
                    synchronized (section) {
                        byte previous = section.getNonEmptyChildren();
                        if (this.world.storage.loadSection(section) == 0) {
                            int flags = WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_DONT_SAVE;
                            if (previous != section.getNonEmptyChildren()) flags |= WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT;
                            this.world.markDirty(section, flags, WorldEngine.ALL_MESH_NEIGHBORS);
                        }
                    }
                } finally {
                    section.release();
                }
            } catch (RuntimeException failure) {
                Logger.warn("Could not refresh local Voxy section " + WorldEngine.pprintPos(key)
                        + " in " + this.dimension.location(), failure);
            }
        }
    }

    public synchronized void stopUpdates() {
        this.subscription.compareAndSet(this, null);
        synchronized (this.pending) {
            this.active = false;
            this.pending.clear();
        }
    }

    @Override
    public void close() {
        this.stopUpdates();
        this.world.free();
    }
}
