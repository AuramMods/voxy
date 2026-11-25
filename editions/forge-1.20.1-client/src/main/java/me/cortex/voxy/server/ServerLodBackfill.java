package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorChunkMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Main-thread selection of saved columns. ChunkMap owns disk reads and upgrades. */
final class ServerLodBackfill {
    private record Anchor(ServerLevel level, ChunkPos center, int radius) {}
    private record Column(ResourceKey<Level> dimension, long position) {}

    private final ServerLodService service;
    private final Executor worker;
    private final Map<Anchor, NearChunkCursor> cursors = new LinkedHashMap<>();
    private final Map<Column, CompletableFuture<Boolean>> active = new HashMap<>();
    private final Set<Anchor> finishedOffline = new HashSet<>();
    private final Set<Column> examined = java.util.Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Column, Boolean> eldest) {
            return this.size() > 65_536;
        }
    });
    private int imported;

    ServerLodBackfill(ServerLodService service, Executor worker) {
        this.service = service;
        this.worker = worker;
    }

    void tick() {
        this.active.entrySet().removeIf(entry -> {
            var future = entry.getValue();
            if (!future.isDone()) return false;
            try {
                if (future.join()) this.imported++;
            } catch (RuntimeException error) {
                Logger.warn("Saved Voxy backfill failed for " + entry.getKey() + ": " + error);
            }
            return true;
        });

        var anchors = this.anchors();
        if (!this.cursors.keySet().equals(anchors)) {
            this.cursors.keySet().retainAll(anchors);
            for (var anchor : anchors) {
                this.cursors.computeIfAbsent(anchor, value -> new NearChunkCursor(value.center(), value.radius()));
            }
            this.examined.clear();
        }

        int limit = ServerVoxyConfig.CHUNK_JOBS.get();
        for (int visited = 0; visited < 128 && this.active.size() < limit; visited++) {
            var next = this.cursors.entrySet().stream().filter(entry -> entry.getValue().peek() != null)
                    .min(Comparator.<Map.Entry<Anchor, NearChunkCursor>>comparingLong(
                                    entry -> entry.getValue().peek().distanceSquared())
                            .thenComparing(entry -> entry.getKey().level().dimension().location())
                            .thenComparingLong(entry -> entry.getValue().peek().position().toLong()))
                    .orElse(null);
            if (next == null) break;
            var level = next.getKey().level();
            var pos = next.getValue().peek().position();
            next.getValue().advance();
            var key = new Column(level.dimension(), pos.toLong());
            if (!level.getWorldBorder().isWithinBounds(pos) || !this.examined.add(key) || this.active.containsKey(key)) continue;

            var dimension = this.service.dimension(level);
            var map = (AccessorChunkMap) level.getChunkSource().chunkMap;
            var future = CompletableFuture.supplyAsync(() -> dimension.storage().hasColumn(pos.toLong()), this.worker)
                    .thenCompose(exists -> exists ? CompletableFuture.completedFuture(false)
                            : map.voxy$readSavedChunk(pos).thenApplyAsync(saved -> {
                                var snapshot = saved.flatMap(tag -> ServerChunkSnapshot.fromSaved(level, pos, tag));
                                if (snapshot.isEmpty()) return false;
                                synchronized (dimension) {
                                    // Another anchor may have queued the same work when its center changed.
                                    if (dimension.storage().hasColumn(pos.toLong())) return false;
                                    ServerColumnWriter.commit(dimension.world(), dimension.storage(), snapshot.get());
                                }
                                return true;
                            }, this.worker));
            this.active.put(key, future);
        }
    }

    private Set<Anchor> anchors() {
        int radius = ServerVoxyConfig.RADIUS.get();
        var server = this.service.server();
        var players = server.getPlayerList().getPlayers();
        var anchors = new java.util.LinkedHashSet<Anchor>();
        var occupied = new HashSet<ResourceKey<Level>>();
        for (var player : players) {
            var level = player.serverLevel();
            occupied.add(level.dimension());
            anchors.add(new Anchor(level, player.chunkPosition(), radius));
        }
        for (String configured : ServerVoxyConfig.SPAWN_DIMENSIONS.get()) {
            var id = new net.minecraft.resources.ResourceLocation(configured);
            var key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
            if (!players.isEmpty() && !occupied.contains(key)) continue;
            var level = server.getLevel(key);
            if (level == null) continue;
            BlockPos spawn = level == server.overworld() ? level.getSharedSpawnPos() : BlockPos.ZERO;
            for (String value : ServerVoxyConfig.SPAWN_ANCHORS.get()) {
                var anchor = ServerVoxyConfig.parseAnchor(value);
                if (anchor != null && anchor.dimension().equals(id)) {
                    spawn = new BlockPos(anchor.x(), 0, anchor.z());
                    break;
                }
            }
            var spawnAnchor = new Anchor(level, new ChunkPos(spawn), radius);
            if (players.isEmpty()) {
                if (this.finishedOffline.contains(spawnAnchor)) continue;
                var cursor = this.cursors.get(spawnAnchor);
                if (cursor != null && cursor.peek() == null
                        && this.active.keySet().stream().noneMatch(column -> column.dimension().equals(key))) {
                    this.finishedOffline.add(spawnAnchor);
                    continue;
                }
                anchors.add(spawnAnchor);
                break;
            }
            anchors.add(spawnAnchor);
        }
        return anchors;
    }

    int drainImports() {
        int count = this.imported;
        this.imported = 0;
        return count;
    }
}
