package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorChunkMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Owns job and ticket lifetimes on the server tick thread. */
final class ServerLodGenerator {
    record Report(int imported, int generated, Set<ResourceLocation> generating, int tickets) {}

    private record Anchor(ServerLevel level, ChunkPos center, int radius) {
        long distance(ChunkPos position) {
            long dx = (long) this.center.x - position.x;
            long dz = (long) this.center.z - position.z;
            long distance = dx * dx + dz * dz;
            return distance <= (long) this.radius * this.radius ? distance : Long.MAX_VALUE;
        }
    }
    private record Column(ResourceKey<Level> dimension, long position) {}
    private record Candidate(Anchor anchor, ChunkPos position, long distance) {
        Column column() {
            return new Column(this.anchor.level().dimension(), this.position.toLong());
        }
    }
    private record Lookup(boolean covered, ServerChunkSnapshot saved) {}
    private record Retry(long readyAt, int attempts) {}

    private static final boolean TRACE = Boolean.getBoolean("voxy.traceServerLodJobs");

    private static final class Job {
        final Candidate target;
        final ServerLodService.Dimension dimension;
        final CompletableFuture<Lookup> lookup;
        final int attempts;
        ServerChunkTicket ticket;
        CompletableFuture<long[]> write;
        boolean generated;
        boolean needsTicket;

        Job(Candidate target, ServerLodService.Dimension dimension, CompletableFuture<Lookup> lookup, int attempts) {
            this.target = target;
            this.dimension = dimension;
            this.lookup = lookup;
            this.attempts = attempts;
        }

        void releaseTicket() {
            if (this.ticket != null) {
                this.ticket.close();
                this.ticket = null;
                if (TRACE) Logger.info("Released Voxy chunk ticket " + this.target.column());
            }
        }
    }

    private static final Comparator<Candidate> PRIORITY = Comparator.comparingLong(Candidate::distance)
            .thenComparing(candidate -> candidate.anchor.level().dimension().location())
            .thenComparingInt(candidate -> candidate.position.x)
            .thenComparingInt(candidate -> candidate.position.z);

    private final ServerLodService service;
    private final Executor worker;
    private final Map<Anchor, NearChunkCursor> cursors = new LinkedHashMap<>();
    private final Map<Column, Job> active = new LinkedHashMap<>();
    private final Map<Column, Retry> retries = new HashMap<>();
    private final Set<Anchor> finishedOffline = new HashSet<>();
    private final Set<ServerLevel> unloaded = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Set<Column> examined = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Column, Boolean> eldest) {
            return this.size() > 65_536;
        }
    });
    private final Set<ResourceLocation> workedDimensions = new LinkedHashSet<>();
    private boolean generationOn;
    private boolean hadPlayers;
    private int imported;
    private int generated;

    ServerLodGenerator(ServerLodService service, Executor worker) {
        this.service = service;
        this.worker = worker;
        this.generationOn = ServerVoxyConfig.GENERATE.get();
    }

    void tick(boolean playersOnline, int limit) {
        boolean enabled = ServerVoxyConfig.GENERATE.get();
        if (this.generationOn != enabled) {
            this.generationOn = enabled;
            this.finishedOffline.clear();
            this.cursors.clear();
            this.examined.clear();
        }

        if (this.hadPlayers != playersOnline) {
            this.hadPlayers = playersOnline;
            this.cursors.clear();
            this.examined.clear();
        }
        Set<Anchor> anchors = this.anchors();

        this.active.entrySet().removeIf(entry -> {
            var job = entry.getValue();
            try {
                return this.advance(job, priorityAt(anchors, entry.getKey()) != null);
            } catch (RuntimeException failure) {
                this.failed(entry.getKey(), job, failure);
                return true;
            }
        });

        if (!this.cursors.keySet().equals(anchors)) {
            this.cursors.keySet().retainAll(anchors);
            for (var anchor : anchors) {
                this.cursors.computeIfAbsent(anchor, value -> new NearChunkCursor(value.center(), value.radius()));
            }
            this.examined.clear();
        }

        int[] visits = {128};
        for (int count = 0; count < limit && this.active.size() < limit; count++) {
            var next = this.nextCandidate(anchors, visits);
            if (next == null) break;
            this.scheduleNext(next);
        }
        this.issueTickets(anchors, visits, limit);
    }

    private Candidate nextCandidate(Set<Anchor> anchors, int[] visits) {
        var next = this.retries.size() < 2048 ? this.nextFresh(visits) : null;
        var retry = this.nextRetry(anchors, System.nanoTime());
        return retry != null && (next == null || PRIORITY.compare(retry, next) < 0) ? retry : next;
    }

    private void scheduleNext(Candidate candidate) {
        var retry = this.retries.remove(candidate.column());
        if (retry == null) {
            this.cursors.get(candidate.anchor()).advance();
            this.examined.add(candidate.column());
        }
        this.schedule(candidate, retry == null ? 0 : retry.attempts());
    }

    private void issueTickets(Set<Anchor> anchors, int[] visits, int limit) {
        if (!this.generationOn || visits[0] < 0) return;
        long running = this.active.values().stream().filter(job -> job.ticket != null || job.write != null).count();
        var ready = this.active.entrySet().stream()
                .filter(entry -> entry.getValue().write == null && entry.getValue().ticket == null)
                .map(entry -> priorityAt(anchors, entry.getKey()))
                .filter(java.util.Objects::nonNull)
                .sorted(PRIORITY)
                .toList();
        if (ready.isEmpty() || running >= limit) return;
        var frontier = this.nextCandidate(anchors, visits);
        if (visits[0] < 0) return;
        for (var candidate : ready) {
            if (running >= limit) break;
            Job job = this.active.get(candidate.column());
            if (frontier != null && PRIORITY.compare(frontier, candidate) < 0) {
                // A new anchor can expose closer work while all slots are occupied by saved-chunk lookups.
                if (job.needsTicket) {
                    this.active.remove(candidate.column());
                    this.retries.put(candidate.column(), new Retry(0, job.attempts));
                    this.scheduleNext(frontier);
                }
                break;
            }
            // Resolve nearer candidates before issuing a ticket for a farther one.
            if (!job.needsTicket) break;
            var level = candidate.anchor().level();
            try {
                job.ticket = new ServerChunkTicket(level.getChunkSource(), candidate.position());
                running++;
                this.workedDimensions.add(level.dimension().location());
                if (TRACE) Logger.info("Issued Voxy chunk ticket " + candidate.column()
                        + " at distance squared " + candidate.distance());
            } catch (RuntimeException failure) {
                this.failed(candidate.column(), job, failure);
                this.active.remove(candidate.column());
            }
        }
    }

    private void failed(Column column, Job job, RuntimeException failure) {
        job.releaseTicket();
        int attempts = job.attempts + 1;
        long delay = Math.min(60, 1L << Math.min(attempts, 6));
        this.retries.put(column, new Retry(System.nanoTime() + TimeUnit.SECONDS.toNanos(delay), attempts));
        if (attempts == 1 || attempts % 8 == 0) {
            Logger.warn("Voxy chunk job failed for " + column + ", retry " + attempts + ": " + failure);
        }
    }

    private static Candidate priorityAt(Set<Anchor> anchors, Column column) {
        var position = new ChunkPos(column.position());
        Candidate best = null;
        for (var anchor : anchors) {
            if (!applies(anchor, column)) continue;
            var candidate = new Candidate(anchor, position, anchor.distance(position));
            if (best == null || PRIORITY.compare(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    private Candidate nextFresh(int[] visits) {
        while (visits[0]-- > 0) {
            Candidate nearest = null;
            for (var entry : this.cursors.entrySet()) {
                var candidate = entry.getValue().peek();
                if (candidate == null) continue;
                var current = new Candidate(entry.getKey(), candidate.position(), candidate.distanceSquared());
                if (nearest == null || PRIORITY.compare(current, nearest) < 0) nearest = current;
            }
            if (nearest == null) return null;
            var key = nearest.column();
            if (!this.examined.contains(key) && !this.retries.containsKey(key) && !this.active.containsKey(key)
                    && nearest.anchor.level().getWorldBorder().isWithinBounds(nearest.position())) {
                return nearest;
            }
            this.cursors.get(nearest.anchor()).advance();
        }
        return null;
    }

    private Candidate nextRetry(Set<Anchor> anchors, long now) {
        Candidate nearest = null;
        for (var entry : this.retries.entrySet()) {
            if (now < entry.getValue().readyAt()) continue;
            var pos = new ChunkPos(entry.getKey().position());
            for (var anchor : anchors) {
                if (!anchor.level().dimension().equals(entry.getKey().dimension())) continue;
                long distance = anchor.distance(pos);
                if (distance == Long.MAX_VALUE || !anchor.level().getWorldBorder().isWithinBounds(pos)) continue;
                var candidate = new Candidate(anchor, pos, distance);
                if (nearest == null || PRIORITY.compare(candidate, nearest) < 0) nearest = candidate;
            }
        }
        return nearest;
    }

    private void schedule(Candidate target, int attempts) {
        var level = target.anchor.level();
        var dimension = this.service.dimension(level);
        var map = (AccessorChunkMap) level.getChunkSource().chunkMap;
        var position = target.position();
        var future = CompletableFuture.supplyAsync(() -> dimension.storage().hasColumn(position.toLong()), this.worker)
                .thenCompose(covered -> covered ? CompletableFuture.completedFuture(new Lookup(true, null))
                        : map.voxy$readSavedChunk(position).thenApplyAsync(saved -> new Lookup(false,
                        saved.flatMap(tag -> ServerChunkSnapshot.fromSaved(level, position, tag)).orElse(null)), this.worker));
        this.active.put(target.column(), new Job(target, dimension, future, attempts));
    }

    private boolean advance(Job job, boolean eligible) {
        if (job.write != null) {
            if (!job.write.isDone()) return false;
            var changed = job.write.join();
            if (changed != null) {
                this.service.publishLocal(job.target.anchor.level(), changed);
                if (job.generated) this.generated++;
                else this.imported++;
            }
            return true;
        }
        if (!eligible) {
            job.releaseTicket();
            this.examined.remove(job.target.column());
            return job.lookup.isDone();
        }
        var level = job.target.anchor.level();
        var pos = job.target.position();
        if (job.ticket != null) {
            if (!this.generationOn) {
                job.releaseTicket();
                return true;
            }
            this.workedDimensions.add(level.dimension().location());
            var chunk = job.ticket.poll();
            if (chunk == null) return false;
            ServerChunkSnapshot snapshot;
            try {
                snapshot = ServerChunkSnapshot.fromLive(level, chunk);
            } finally {
                job.releaseTicket();
            }
            this.write(job, snapshot);
            return false;
        }
        if (!job.lookup.isDone()) return false;
        var result = job.lookup.join();
        if (result.covered()) return true;
        var loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (loaded != null && loaded.isLightCorrect()) {
            this.write(job, ServerChunkSnapshot.fromLive(level, loaded));
        } else if (result.saved() != null) {
            this.write(job, result.saved());
        } else if (this.generationOn) {
            job.generated = loaded == null;
            job.needsTicket = true;
        } else {
            return true;
        }
        return false;
    }

    private void write(Job job, ServerChunkSnapshot snapshot) {
        var level = job.target.anchor().level();
        long key = snapshot.position().toLong();
        job.write = CompletableFuture.supplyAsync(() -> {
            synchronized (job.dimension) {
                if (job.dimension.storage().hasColumn(key) || this.service.pendingEdit(level, key)) return null;
                return ServerColumnWriter.commit(job.dimension.world(), job.dimension.storage(), snapshot,
                        () -> !this.service.pendingEdit(level, key));
            }
        }, this.worker);
    }

    private Set<Anchor> anchors() {
        int radius = ServerVoxyConfig.RADIUS.get();
        var server = this.service.server();
        var players = server.getPlayerList().getPlayers();
        var anchors = new LinkedHashSet<Anchor>();
        if (!players.isEmpty()) {
            var occupied = new HashSet<ResourceKey<Level>>();
            for (var player : players) {
                var level = player.serverLevel();
                if (this.unloaded.contains(level)) continue;
                occupied.add(level.dimension());
                anchors.add(new Anchor(level, player.chunkPosition(), radius));
            }
            for (String configured : ServerVoxyConfig.SPAWN_DIMENSIONS.get()) {
                var key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(configured));
                if (occupied.contains(key)) anchors.add(spawnAnchor(server.getLevel(key), radius));
            }
        } else {
            for (String configured : ServerVoxyConfig.SPAWN_DIMENSIONS.get()) {
                var key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(configured));
                var level = server.getLevel(key);
                if (level == null || this.unloaded.contains(level)) continue;
                var anchor = spawnAnchor(level, radius);
                if (this.finishedOffline.contains(anchor)) continue;
                var cursor = this.cursors.get(anchor);
                if (cursor != null && cursor.peek() == null && !this.isUnfinished(anchor)) {
                    this.finishedOffline.add(anchor);
                    String finished = this.generationOn ? "finished idle spawn coverage" : "finished idle saved-column scan";
                    Logger.info("Voxy " + finished + " in " + key.location() + " at radius " + radius);
                    continue;
                }
                anchors.add(anchor);
                break;
            }
        }
        return anchors;
    }

    private boolean isUnfinished(Anchor anchor) {
        return this.active.keySet().stream().anyMatch(key -> applies(anchor, key))
                || this.retries.keySet().stream().anyMatch(key -> applies(anchor, key));
    }

    private static boolean applies(Anchor anchor, Column column) {
        var position = new ChunkPos(column.position());
        return column.dimension().equals(anchor.level().dimension()) && anchor.distance(position) != Long.MAX_VALUE
                && anchor.level().getWorldBorder().isWithinBounds(position);
    }

    private static Anchor spawnAnchor(ServerLevel level, int radius) {
        var id = level.dimension().location();
        BlockPos spawn = BlockPos.ZERO;
        if (level == level.getServer().overworld()) {
            var data = level.getLevelData();
            spawn = new BlockPos(data.getXSpawn(), 0, data.getZSpawn());
            var border = level.getWorldBorder();
            if (!border.isWithinBounds(spawn)) spawn = BlockPos.containing(border.getCenterX(), 0, border.getCenterZ());
        }
        for (String value : ServerVoxyConfig.SPAWN_ANCHORS.get()) {
            var anchor = ServerVoxyConfig.parseAnchor(value);
            if (anchor != null && anchor.dimension().equals(id)) {
                spawn = new BlockPos(anchor.x(), 0, anchor.z());
                break;
            }
        }
        return new Anchor(level, new ChunkPos(spawn), radius);
    }

    Report report() {
        int tickets = (int) this.active.values().stream().filter(job -> job.ticket != null).count();
        var result = new Report(this.imported, this.generated, Set.copyOf(this.workedDimensions), tickets);
        this.imported = 0;
        this.generated = 0;
        this.workedDimensions.clear();
        return result;
    }

    void close() {
        this.active.values().forEach(Job::releaseTicket);
    }

    void unload(ServerLevel level) {
        this.unloaded.add(level);
        this.active.values().stream().filter(job -> job.target.anchor().level() == level).forEach(Job::releaseTicket);
    }
}
