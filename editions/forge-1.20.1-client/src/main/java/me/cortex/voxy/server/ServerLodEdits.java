package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Collects edits at column granularity and publishes them after their lighting has settled. */
final class ServerLodEdits {
    private static final int MAX_WRITES = 4;
    private static final int QUIET_TICKS = 5;
    private static final int MAX_DELAY_TICKS = 20;

    private record Column(ServerLevel level, long position) {}

    private static final class Pending {
        final AtomicBoolean relit = new AtomicBoolean();
        volatile long stamp;
        int readyAt;
        int firstAt;
        long writingStamp;
        CompletableFuture<long[]> write;

        Pending(int now) {
            this.firstAt = now;
            this.changed(now);
        }

        void changed(int now) {
            this.stamp++;
            this.readyAt = Math.min(now + QUIET_TICKS, this.firstAt + MAX_DELAY_TICKS);
        }
    }

    private final ServerLodService service;
    private final Executor worker;
    private final Map<Column, Pending> pending = new ConcurrentHashMap<>();
    private int inFlight;

    ServerLodEdits(ServerLodService service, Executor worker) {
        this.service = service;
        this.worker = worker;
    }

    void blockChanged(ServerLevel level, ChunkPos position) {
        int now = level.getServer().getTickCount();
        for (int z = position.z - 1; z <= position.z + 1; z++) {
            for (int x = position.x - 1; x <= position.x + 1; x++) {
                if ((x != position.x || z != position.z) && level.getChunkSource().getChunkNow(x, z) == null) continue;
                var key = new Column(level, ChunkPos.asLong(x, z));
                var edit = this.pending.get(key);
                if (edit == null) this.pending.put(key, new Pending(now));
                else edit.changed(now);
            }
        }
    }

    void lightChanged(ServerLevel level, long position) {
        var edit = this.pending.get(new Column(level, position));
        if (edit != null) edit.relit.set(true);
    }

    boolean isPending(ServerLevel level, long position) {
        return this.pending.containsKey(new Column(level, position));
    }

    void tick(int now) {
        for (var entry : this.pending.entrySet()) {
            var column = entry.getKey();
            var edit = entry.getValue();
            if (edit.relit.getAndSet(false)) edit.changed(now);
            if (edit.write != null) {
                if (!edit.write.isDone()) continue;
                this.inFlight--;
                try {
                    var changed = edit.write.join();
                    if (changed != null) this.service.publish(column.level, changed);
                    if (changed != null && edit.writingStamp == edit.stamp) {
                        this.pending.remove(column, edit);
                        continue;
                    }
                } catch (RuntimeException failure) {
                    edit.readyAt = now + 100;
                    Logger.warn("Voxy could not refresh an edited column at " + new ChunkPos(column.position)
                            + " in " + column.level.dimension().location() + ": " + failure);
                }
                edit.write = null;
            }
            if (now < edit.readyAt || this.inFlight >= MAX_WRITES) continue;
            var position = new ChunkPos(column.position);
            var chunk = column.level.getChunkSource().getChunkNow(position.x, position.z);
            if (chunk != null && !chunk.isLightCorrect()) continue;
            try {
                this.schedule(column, edit, chunk);
                edit.firstAt = now;
            } catch (RuntimeException failure) {
                edit.readyAt = now + 100;
                Logger.warn("Voxy could not snapshot an edited column at " + position + ": " + failure);
            }
        }
    }

    void flush(int now) {
        this.pending.values().forEach(edit -> {
            if (edit.relit.getAndSet(false)) edit.changed(now);
            edit.readyAt = now;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!this.pending.isEmpty() && System.nanoTime() < deadline) {
            this.tick(now);
            var writes = this.pending.values().stream().map(edit -> edit.write)
                    .filter(java.util.Objects::nonNull).toArray(CompletableFuture[]::new);
            if (writes.length == 0) break;
            try {
                CompletableFuture.anyOf(writes).handle((value, failure) -> null)
                        .get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                break;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new IllegalStateException(failure.getCause());
            }
        }
        if (!this.pending.isEmpty()) Logger.warn("Voxy stopped with " + this.pending.size() + " pending LOD edits");
    }

    void unloading(ServerLevel level, LevelChunk chunk) {
        var column = new Column(level, chunk.getPos().toLong());
        var edit = this.pending.get(column);
        if (edit != null && edit.write == null && this.inFlight < MAX_WRITES && chunk.isLightCorrect()) {
            this.schedule(column, edit, chunk);
        }
    }

    private void schedule(Column column, Pending edit, LevelChunk chunk) {
        var dimension = this.service.dimension(column.level);
        long stamp = edit.stamp;
        edit.writingStamp = stamp;
        CompletableFuture<ServerChunkSnapshot> snapshot;
        if (chunk != null) {
            snapshot = CompletableFuture.completedFuture(ServerChunkSnapshot.fromLive(column.level, chunk));
        } else {
            var position = new ChunkPos(column.position);
            var map = (AccessorChunkMap) column.level.getChunkSource().chunkMap;
            snapshot = map.voxy$readSavedChunk(position).thenApplyAsync(saved -> saved
                    .flatMap(tag -> ServerChunkSnapshot.fromSaved(column.level, position, tag))
                    .orElseThrow(() -> new IllegalStateException("The edited chunk is not accessible yet")), this.worker);
        }
        edit.write = snapshot.thenApplyAsync(data -> {
            if (edit.stamp != stamp || edit.relit.get()) return null;
            var prepared = data.voxelize(dimension.world().getMapper());
            synchronized (dimension) {
                if (edit.stamp != stamp || edit.relit.get()) return null;
                return ServerColumnWriter.commit(dimension.world(), dimension.storage(), prepared,
                        () -> edit.stamp == stamp && !edit.relit.get());
            }
        }, this.worker);
        this.inFlight++;
    }
}
