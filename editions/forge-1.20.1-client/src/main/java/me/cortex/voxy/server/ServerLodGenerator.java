package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorChunkMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.function.Supplier;

/** Owns job and ticket lifetimes on the server tick thread. */
final class ServerLodGenerator {
    record Report(int imported, int generated, Set<ResourceLocation> generating, int tickets) {}

    private record Anchor(ServerLevel level, ChunkPos center, int radius, ServerPlayer player, boolean onlineSpawn) {
        GenerationChunkCursor.View view() {
            if (this.player != null) return GenerationChunkCursor.View.fromYaw(this.player.getYRot());
            return this.onlineSpawn ? GenerationChunkCursor.View.ONLINE_SPAWN : GenerationChunkCursor.View.OMNIDIRECTIONAL;
        }

        Anchor scanKey() {
            // Share progress for identical terrain areas even when the scheduling priority changes.
            return this.player == null && !this.onlineSpawn ? this : new Anchor(this.level, this.center, this.radius, null, false);
        }

        long distance(ChunkPos position) {
            long dx = (long) this.center.x - position.x;
            long dz = (long) this.center.z - position.z;
            long distance = dx * dx + dz * dz;
            return distance <= (long) this.radius * this.radius ? distance : Long.MAX_VALUE;
        }
    }
    private record Column(ResourceKey<Level> dimension, long position) {}
    private record Candidate(Anchor anchor, ChunkPos position, long distance, double priority) {
        Candidate(Anchor anchor, ChunkPos position, long distance, GenerationChunkCursor.View view) {
            this(anchor, position, distance, view.priority(anchor.center(), position, distance));
        }

        Column column() {
            return new Column(this.anchor.level().dimension(), this.position.toLong());
        }
    }
    private record Lookup(boolean covered, CompoundTag saved) {}
    private record Retry(long readyAt, int attempts) {}

    private static final boolean TRACE = Boolean.getBoolean("voxy.traceServerLodJobs");
    private static final int MAX_BACKFILL_JOBS = 2;
    private static final int MAX_DEFERRED_BACKFILLS = 4096;
    private static final int MAX_RETRIES_PER_SCAN = 2048;

    private static final class Job {
        final Candidate target;
        final ServerLodService.Dimension dimension;
        final CompletableFuture<Lookup> lookup;
        final int attempts;
        ServerChunkTicket ticket;
        CompletableFuture<long[]> write;
        boolean backfill;
        boolean generated;
        boolean needsTicket;

        Job(Candidate target, ServerLodService.Dimension dimension, CompletableFuture<Lookup> lookup, int attempts,
                boolean backfill) {
            this.target = target;
            this.dimension = dimension;
            this.lookup = lookup;
            this.attempts = attempts;
            this.backfill = backfill;
        }

        void releaseTicket() {
            if (this.ticket != null) {
                this.ticket.close();
                this.ticket = null;
                if (TRACE) Logger.info("Released Voxy chunk ticket " + this.target.column());
            }
        }
    }

    private static final Comparator<Candidate> PRIORITY = Comparator.comparingDouble(Candidate::priority)
            .thenComparingLong(Candidate::distance)
            .thenComparing(candidate -> candidate.anchor.level().dimension().location())
            .thenComparingInt(candidate -> candidate.position.x)
            .thenComparingInt(candidate -> candidate.position.z);

    private final ServerLodService service;
    private final Executor worker;
    private final Map<Anchor, GenerationChunkCursor> cursors = new LinkedHashMap<>();
    private final Map<Column, Job> active = new LinkedHashMap<>();
    private final Map<Column, Integer> deferredBackfills = new LinkedHashMap<>();
    private final Map<Column, Retry> retries = new HashMap<>();
    private final Set<Anchor> finishedSpawnScans = new HashSet<>();
    private Set<Anchor> previousEligible = Set.of();
    private final Set<ServerLevel> unloaded = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Set<Column> examined = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Column, Boolean> eldest) {
            return this.size() > 65_536;
        }
    });
    private final Set<ResourceLocation> workedDimensions = new LinkedHashSet<>();
    private boolean generationOn;
    private int imported;
    private int generated;

    ServerLodGenerator(ServerLodService service, Executor worker) {
        this.service = service;
        this.worker = worker;
        this.generationOn = service.config().get(ServerVoxyConfig.GENERATE);
    }

    void tick(boolean playersOnline, int limit) {
        boolean enabled = this.service.config().get(ServerVoxyConfig.GENERATE);
        if (this.generationOn != enabled) {
            this.generationOn = enabled;
            this.finishedSpawnScans.clear();
            this.cursors.clear();
            this.examined.clear();
        }

        Set<Anchor> foreground = new LinkedHashSet<>();
        Set<Anchor> spawnAnchors = new LinkedHashSet<>();
        this.collectAnchors(foreground, spawnAnchors);
        Set<Anchor> eligible = new LinkedHashSet<>(foreground);
        if (!playersOnline || this.generationOn) eligible.addAll(spawnAnchors);

        if (!this.previousEligible.equals(eligible)) {
            var retained = new HashSet<Anchor>();
            for (var anchor : eligible) retained.add(anchor.scanKey());
            this.cursors.keySet().retainAll(retained);
            this.finishedSpawnScans.retainAll(retained);
            this.deferredBackfills.keySet().removeIf(key -> !inScope(eligible, key));
            this.examined.clear();
            this.previousEligible = eligible;
        }
        for (var anchor : foreground) this.cursor(anchor);

        int backfillLimit = this.generationOn ? Math.min(limit, MAX_BACKFILL_JOBS) : limit;
        int[] backfillSlots = {backfillLimit - this.activeBackfills()};
        this.active.entrySet().removeIf(entry -> {
            var job = entry.getValue();
            try {
                return this.advance(job, inScope(eligible, entry.getKey()), backfillSlots);
            } catch (RuntimeException failure) {
                this.failed(entry.getKey(), job, failure);
                return true;
            }
        });

        Anchor idle = null;
        if (!playersOnline || (this.generationOn && this.hasFullForegroundCoverage(foreground))) {
            idle = this.nextIdleAnchor(spawnAnchors);
        }

        Set<Anchor> ticketAnchors = idle == null ? foreground : Set.of(idle);
        this.parkUnissuedTickets(ticketAnchors);
        this.scheduleBackfills(foreground, backfillLimit - this.activeBackfills());
        if (idle != null) this.scheduleBackfills(ticketAnchors, backfillLimit - this.activeBackfills());

        int[] visits = {128};
        int available = limit - this.active.size() + (this.generationOn ? this.activeBackfills() : 0);
        for (int count = 0; count < available; count++) {
            var next = this.nextCandidate(ticketAnchors, visits);
            if (next == null) break;
            this.scheduleNext(next);
        }
        this.issueTickets(ticketAnchors, visits, limit);
    }

    private GenerationChunkCursor cursor(Anchor anchor) {
        return this.cursors.computeIfAbsent(anchor.scanKey(), key -> new GenerationChunkCursor(key.center(), key.radius()));
    }

    private boolean hasFullForegroundCoverage(Set<Anchor> anchors) {
        for (var anchor : anchors) {
            if (!this.cursor(anchor).isExhausted()) return false;
        }
        for (var entry : this.active.entrySet()) {
            var job = entry.getValue();
            // Only unresolved lookups and Minecraft generation block background tickets. Writes already have full terrain.
            if (!job.backfill && job.write == null && inScope(anchors, entry.getKey())) return false;
        }
        return this.retries.keySet().stream().noneMatch(key -> inScope(anchors, key));
    }

    private void parkUnissuedTickets(Set<Anchor> anchors) {
        var jobs = this.active.entrySet().iterator();
        while (jobs.hasNext()) {
            var entry = jobs.next();
            var job = entry.getValue();
            if (!job.needsTicket || job.ticket != null || job.write != null || inScope(anchors, entry.getKey())) continue;
            // The cursor has passed this column. Keep it retryable while unissued background work yields its slot.
            this.retries.put(entry.getKey(), new Retry(0, job.attempts));
            jobs.remove();
        }
    }

    private int activeBackfills() {
        return (int) this.active.values().stream().filter(job -> job.backfill).count();
    }

    private void scheduleBackfills(Set<Anchor> anchors, int available) {
        while (available-- > 0 && !this.deferredBackfills.isEmpty()) {
            Candidate nearest = null;
            for (var key : this.deferredBackfills.keySet()) {
                var candidate = priorityAt(anchors, key);
                if (candidate != null && (nearest == null || PRIORITY.compare(candidate, nearest) < 0)) {
                    nearest = candidate;
                }
            }
            if (nearest == null) break;
            int attempts = this.deferredBackfills.remove(nearest.column());
            this.schedule(nearest, attempts, true);
        }
    }

    private Candidate nextCandidate(Set<Anchor> anchors, int[] visits) {
        boolean canScan = this.retries.size() < MAX_RETRIES_PER_SCAN
                || this.retries.keySet().stream().filter(key -> inScope(anchors, key))
                .limit(MAX_RETRIES_PER_SCAN).count() < MAX_RETRIES_PER_SCAN;
        var next = canScan ? this.nextFresh(anchors, visits) : null;
        var retry = this.nextRetry(anchors, System.nanoTime());
        return retry != null && (next == null || PRIORITY.compare(retry, next) < 0) ? retry : next;
    }

    private void scheduleNext(Candidate candidate) {
        var retry = this.retries.remove(candidate.column());
        if (retry == null) {
            this.cursor(candidate.anchor()).advance(candidate.position());
            this.examined.add(candidate.column());
        }
        this.schedule(candidate, retry == null ? 0 : retry.attempts(), false);
    }

    private void issueTickets(Set<Anchor> anchors, int[] visits, int limit) {
        if (!this.generationOn || visits[0] < 0) return;
        long running = this.active.values().stream()
                .filter(job -> !job.backfill && (job.ticket != null || job.write != null)).count();
        var ready = this.active.entrySet().stream()
                .filter(entry -> !entry.getValue().backfill && entry.getValue().write == null && entry.getValue().ticket == null)
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
            var candidate = new Candidate(anchor, position, anchor.distance(position), anchor.view());
            if (best == null || PRIORITY.compare(candidate, best) < 0) best = candidate;
        }
        return best;
    }

    private Candidate nextFresh(Set<Anchor> anchors, int[] visits) {
        while (visits[0]-- > 0) {
            Candidate nearest = null;
            for (var anchor : anchors) {
                var view = anchor.view();
                var candidate = this.cursor(anchor).peek(view);
                if (candidate == null) continue;
                var current = new Candidate(anchor, candidate.position(), candidate.distanceSquared(), view);
                if (nearest == null || PRIORITY.compare(current, nearest) < 0) nearest = current;
            }
            if (nearest == null) return null;
            var key = nearest.column();
            if (!this.examined.contains(key) && !this.retries.containsKey(key) && !this.active.containsKey(key)
                    && !this.deferredBackfills.containsKey(key)
                    && nearest.anchor.level().getWorldBorder().isWithinBounds(nearest.position())) {
                return nearest;
            }
            this.cursor(nearest.anchor()).advance(nearest.position());
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
                var candidate = new Candidate(anchor, pos, distance, anchor.view());
                if (nearest == null || PRIORITY.compare(candidate, nearest) < 0) nearest = candidate;
            }
        }
        return nearest;
    }

    private void schedule(Candidate target, int attempts, boolean backfill) {
        var level = target.anchor.level();
        var dimension = this.service.dimension(level);
        var map = (AccessorChunkMap) level.getChunkSource().chunkMap;
        var position = target.position();
        var future = CompletableFuture.supplyAsync(() -> dimension.storage().hasColumn(position.toLong()), this.worker)
                .thenCompose(covered -> covered ? CompletableFuture.completedFuture(new Lookup(true, null))
                        : map.voxy$readSavedChunk(position).thenApply(saved -> new Lookup(false,
                        saved.filter(ServerChunkSnapshot::isFull).orElse(null))));
        this.active.put(target.column(), new Job(target, dimension, future, attempts, backfill));
    }

    private boolean advance(Job job, boolean eligible, int[] backfillSlots) {
        if (job.write != null) {
            if (!job.write.isDone()) return false;
            var changed = job.write.join();
            if (changed != null) {
                this.service.publish(job.target.anchor.level(), changed);
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
        boolean readyLive = loaded != null && loaded.isLightCorrect();
        if (readyLive || result.saved() != null) {
            if (!job.backfill) {
                if (backfillSlots[0] <= 0 || !this.deferredBackfills.isEmpty()) {
                    if (this.deferredBackfills.size() >= MAX_DEFERRED_BACKFILLS) return false;
                    // Remember just the column. Holding decoded snapshots would cap how far we can look ahead.
                    this.deferredBackfills.put(job.target.column(), job.attempts);
                    return true;
                }
                backfillSlots[0]--;
                job.backfill = true;
            }
            if (readyLive) {
                this.write(job, ServerChunkSnapshot.fromLive(level, loaded));
            } else {
                this.write(job, () -> ServerChunkSnapshot.fromSaved(level, pos, result.saved()).orElseThrow());
            }
        } else if (this.generationOn) {
            job.backfill = false;
            job.generated = loaded == null;
            job.needsTicket = true;
        } else {
            return true;
        }
        return false;
    }

    private void write(Job job, ServerChunkSnapshot snapshot) {
        this.write(job, () -> snapshot);
    }

    private void write(Job job, Supplier<ServerChunkSnapshot> prepare) {
        var level = job.target.anchor().level();
        long key = job.target.position().toLong();
        job.write = CompletableFuture.supplyAsync(() -> {
            if (this.service.pendingEdit(level, key)) return null;
            try (var column = this.service.voxelize(prepare.get(), job.dimension)) {
                synchronized (job.dimension) {
                    if (job.dimension.storage().hasColumn(key) || this.service.pendingEdit(level, key)) return null;
                    return ServerColumnWriter.commit(job.dimension.world(), job.dimension.storage(), column,
                            () -> !this.service.pendingEdit(level, key));
                }
            }
        }, this.worker);
    }

    private void collectAnchors(Set<Anchor> foreground, Set<Anchor> idle) {
        var config = this.service.config();
        int radius = config.get(ServerVoxyConfig.RADIUS);
        var server = this.service.server();
        var occupied = new HashSet<ResourceKey<Level>>();
        for (var player : server.getPlayerList().getPlayers()) {
            var level = player.serverLevel();
            if (this.unloaded.contains(level)) continue;
            occupied.add(level.dimension());
            foreground.add(new Anchor(level, player.chunkPosition(), radius, player, false));
        }

        for (String configured : config.get(ServerVoxyConfig.SPAWN_DIMENSIONS)) {
            var key = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(configured));
            var level = server.getLevel(key);
            if (level == null || this.unloaded.contains(level)) continue;
            if (occupied.contains(key)) foreground.add(spawnAnchor(level, radius, config, true));
            else idle.add(spawnAnchor(level, radius, config, false));
        }
    }

    private Anchor nextIdleAnchor(Set<Anchor> anchors) {
        for (var anchor : anchors) {
            if (this.finishedSpawnScans.contains(anchor)) continue;
            if (!this.cursor(anchor).isExhausted() || this.isUnfinished(anchor)) return anchor;

            this.finishedSpawnScans.add(anchor);
            String finished = this.generationOn ? "finished idle spawn coverage" : "finished idle saved-column scan";
            Logger.info("Voxy " + finished + " in " + anchor.level().dimension().location() + " at radius " + anchor.radius());
        }
        return null;
    }

    private boolean isUnfinished(Anchor anchor) {
        return this.active.keySet().stream().anyMatch(key -> applies(anchor, key))
                || this.deferredBackfills.keySet().stream().anyMatch(key -> applies(anchor, key))
                || this.retries.keySet().stream().anyMatch(key -> applies(anchor, key));
    }

    private static boolean inScope(Set<Anchor> anchors, Column column) {
        for (var anchor : anchors) {
            if (applies(anchor, column)) return true;
        }
        return false;
    }

    private static boolean applies(Anchor anchor, Column column) {
        if (!column.dimension().equals(anchor.level().dimension())) return false;
        var position = new ChunkPos(column.position());
        return anchor.distance(position) != Long.MAX_VALUE && anchor.level().getWorldBorder().isWithinBounds(position);
    }

    private static Anchor spawnAnchor(ServerLevel level, int radius, ServerVoxyConfig config, boolean online) {
        var id = level.dimension().location();
        BlockPos spawn = BlockPos.ZERO;
        if (level == level.getServer().overworld()) {
            var data = level.getLevelData();
            spawn = new BlockPos(data.getXSpawn(), 0, data.getZSpawn());
            var border = level.getWorldBorder();
            if (!border.isWithinBounds(spawn)) spawn = BlockPos.containing(border.getCenterX(), 0, border.getCenterZ());
        }
        for (String value : config.get(ServerVoxyConfig.SPAWN_ANCHORS)) {
            var anchor = ServerVoxyConfig.parseAnchor(value);
            if (anchor != null && anchor.dimension().equals(id)) {
                spawn = new BlockPos(anchor.x(), 0, anchor.z());
                break;
            }
        }
        return new Anchor(level, new ChunkPos(spawn), radius, null, online);
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
