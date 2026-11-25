package me.cortex.voxy.server;

import com.google.common.hash.Hashing;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.LodSectionStorage.TileVersion;
import me.cortex.voxy.common.config.section.LodSectionStorage.WireTile;
import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.common.network.LodSectionCodec;
import me.cortex.voxy.common.network.RegistryLodPalette;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

final class ServerLodStreams {
    private static final int PLAYER_BYTES_PER_SECOND = 512 * 1024;
    private static final int PLAYER_BURST = PLAYER_BYTES_PER_SECOND + 4096;
    private static final int SERVER_BYTES_PER_SECOND = 2 * 1024 * 1024;
    private static final int MAX_QUERY = LodChannel.MAX_OFFER;
    private static final int MAX_METADATA_LOOKUPS_PER_TICK = 12;
    private static final int MAX_PENDING_CHANGES = 512;
    private static final int SECTIONS_PER_GROUP = 4;

    private static final class Stream {
        final ServerPlayer player;
        final ServerLevel level;
        final ServerLodService.Dimension dimension;
        final long scope;
        final int radius;
        final Set<Long> offered = new HashSet<>();
        final Set<Long> changes = new LinkedHashSet<>();
        final Set<Long> visibleDemand = new LinkedHashSet<>();
        final Set<Long> visibleSubscriptions = new HashSet<>();
        final ArrayDeque<Long> requests = new ArrayDeque<>();
        long bytes = PLAYER_BURST;
        long waitUntil;
        long batch;
        long nextDemand;
        boolean clientReady;
        long nextPass;
        ChunkPos center;
        ChunkPos scanCenter;
        boolean reconcile = true;
        LodSectionCursor cursor;
        CompletableFuture<LodChannel.Offer> offer;
        LodChannel.Offer sentOffer;
        CompletableFuture<ArrayDeque<LodChannel.Section>> sections;
        final ArrayDeque<LodChannel.Section> ready = new ArrayDeque<>();

        Stream(ServerPlayer player, ServerLodService.Dimension dimension, long scope, int radius) {
            this.player = player;
            this.level = player.serverLevel();
            this.dimension = dimension;
            this.scope = scope;
            this.radius = radius;
        }
    }

    private final ServerLodService service;
    private final Executor worker;
    private final Map<UUID, Stream> streams = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger tasks = new java.util.concurrent.atomic.AtomicInteger();
    private long nextScope;
    private long budget = SERVER_BYTES_PER_SECOND;
    private final UUID session = UUID.randomUUID();

    ServerLodStreams(ServerLodService service, Executor worker) {
        this.service = service;
        this.worker = worker;
    }

    void tick() {
        this.budget = Math.min(SERVER_BYTES_PER_SECOND, this.budget + SERVER_BYTES_PER_SECOND / 20);
        int lookups = 0;
        long now = System.nanoTime();
        var online = new HashSet<UUID>();
        for (var player : this.service.server().getPlayerList().getPlayers()) {
            if (!LodChannel.available(player)) continue;
            online.add(player.getUUID());
            int radius = ServerVoxyConfig.RADIUS.get();
            var stream = this.streams.get(player.getUUID());
            if (stream == null || stream.player != player || stream.level != player.serverLevel() || stream.radius != radius) {
                var dimension = this.service.dimension(player.serverLevel());
                stream = new Stream(player, dimension, ++this.nextScope, radius);
                this.streams.put(player.getUUID(), stream);
                LodChannel.send(player, new LodChannel.Scope(this.service.worldId, this.session,
                        stream.level.dimension().location(), stream.scope, radius));
            }
            boolean mayQuery = lookups < MAX_METADATA_LOOKUPS_PER_TICK && this.tasks.get() < 32;
            if (stream.clientReady && this.sendNext(player, stream, now, mayQuery)) lookups++;
        }
        this.streams.keySet().retainAll(online);
    }

    void publish(ServerLevel level, long[] changed) {
        for (var stream : this.streams.values()) {
            if (level != stream.level || stream.center == null) continue;
            for (long key : changed) {
                if (stream.changes.size() >= MAX_PENDING_CHANGES) break;
                if (watches(stream, key, stream.center)) stream.changes.add(key);
            }
        }
    }

    private boolean sendNext(ServerPlayer player, Stream stream, long now, boolean mayQuery) {
        stream.bytes = Math.min(PLAYER_BURST, stream.bytes + PLAYER_BYTES_PER_SECOND / 20);
        var center = player.chunkPosition();
        stream.center = center;
        int step = Math.max(1, Math.min(8, stream.radius / 4));
        if (stream.scanCenter == null || Math.abs(stream.scanCenter.x - center.x) >= step
                || Math.abs(stream.scanCenter.z - center.z) >= step || stream.cursor == null && now >= stream.nextPass) {
            stream.scanCenter = center;
            stream.cursor = new LodSectionCursor(center, stream.radius,
                    stream.level.getMinBuildHeight(), stream.level.getMaxBuildHeight());
        }

        if (stream.sections != null && stream.sections.isDone()) {
            var completed = completed(stream.sections);
            if (completed != null) stream.ready.addAll(completed);
            stream.sections = null;
        }
        while (!stream.ready.isEmpty()) {
            var ready = stream.ready.peekFirst();
            if (LodSectionCursor.inWorldRange(ready.key(), center, stream.radius)) {
                int bytes = ready.data().length + 40;
                if (bytes > stream.bytes || bytes > this.budget) break;
                LodChannel.send(player, ready);
                stream.bytes -= bytes;
                this.budget -= bytes;
            }
            stream.ready.removeFirst();
        }
        if (stream.sections == null && stream.ready.isEmpty() && !stream.requests.isEmpty() && mayQuery) {
            long[] keys = new long[SECTIONS_PER_GROUP];
            int count = 0;
            while (count < keys.length && !stream.requests.isEmpty()) {
                long key = stream.requests.removeFirst();
                if (LodSectionCursor.inWorldRange(key, center, stream.radius)) keys[count++] = key;
            }
            int total = count;
            if (total > 0) {
                stream.sections = this.submit(() -> encodeSections(stream, keys, total));
                return true;
            }
        }
        if (!stream.requests.isEmpty() || !stream.ready.isEmpty() || stream.sections != null) return false;
        if (!stream.offered.isEmpty()) {
            if (now >= stream.waitUntil) this.sendOffer(player, stream, stream.sentOffer, now);
            return false;
        }

        if (stream.offer != null && stream.offer.isDone()) {
            var offer = completed(stream.offer);
            if (offer != null && offer.keys().length != 0) {
                if (!this.sendOffer(player, stream, offer, now)) return false;
                for (int i = 0; i < offer.keys().length; i++) {
                    if (offer.versions()[i] != null) stream.offered.add(offer.keys()[i]);
                }
                if (!stream.offered.isEmpty()) stream.sentOffer = offer;
                stream.offer = null;
                return false;
            }
            stream.offer = null;
        }

        if (stream.offer == null && mayQuery && (stream.cursor != null || !stream.changes.isEmpty()
                || !stream.visibleDemand.isEmpty())) {
            long[] keys = new long[MAX_QUERY];
            int count = 0;
            var visible = stream.visibleDemand.iterator();
            while (count < keys.length && visible.hasNext()) {
                long key = visible.next();
                visible.remove();
                if (LodSectionCursor.inWorldRange(key, center, stream.radius)) keys[count++] = key;
            }
            var changes = stream.changes.iterator();
            int liveLimit = stream.cursor == null ? keys.length : keys.length / 4;
            while (count < liveLimit && changes.hasNext()) {
                long key = changes.next();
                changes.remove();
                if (watches(stream, key, center)) keys[count++] = key;
            }
            int firstScanKey = count;
            while (count < keys.length && stream.cursor != null && stream.cursor.hasNext()) {
                long key = stream.cursor.next();
                if (LodSectionCursor.inRange(key, center, stream.radius)) {
                    keys[count++] = key;
                }
            }
            boolean reconcile = stream.reconcile;
            if (stream.cursor != null && !stream.cursor.hasNext()) {
                stream.cursor = null;
                stream.reconcile = false;
                stream.nextPass = now + 5_000_000_000L;
            }
            int total = count;
            if (total > 0) {
                if (total - firstScanKey > 1) {
                    LodSectionCursor.preferVisible(keys, firstScanKey, total, center, player.getLookAngle());
                }
                long batch = ++stream.batch;
                stream.offer = this.submit(() -> offer(stream, keys, total, reconcile, batch));
                return true;
            }
        }
        return false;
    }

    private boolean sendOffer(ServerPlayer player, Stream stream, LodChannel.Offer offer, long now) {
        int bytes = offer.keys().length * 26 + 16;
        if (bytes > stream.bytes || bytes > this.budget) return false;
        stream.waitUntil = now + 3_000_000_000L;
        LodChannel.send(player, offer);
        stream.bytes -= bytes;
        this.budget -= bytes;
        return true;
    }

    private static boolean watches(Stream stream, long key, ChunkPos center) {
        return LodSectionCursor.inRange(key, center, stream.radius)
                || stream.visibleSubscriptions.contains(key) && LodSectionCursor.inWorldRange(key, center, stream.radius);
    }

    void request(ServerPlayer player, LodChannel.Request request) {
        var stream = this.streams.get(player.getUUID());
        if (stream == null || stream.player != player || stream.scope != request.scope()
                || stream.level != player.serverLevel()) return;
        if (request.batch() == LodChannel.VIEW_READY || request.batch() == LodChannel.VIEW_CHANGED) {
            long now = System.nanoTime();
            if (stream.clientReady && (request.batch() == LodChannel.VIEW_READY || now < stream.nextDemand)) return;
            stream.clientReady = true;
            stream.nextDemand = now + 150_000_000L;
            var wanted = new HashSet<Long>();
            for (long key : request.keys()) {
                if (LodSectionCursor.inWorldRange(key, player.chunkPosition(), stream.radius)) {
                    wanted.add(key);
                    if (!stream.visibleSubscriptions.contains(key)) stream.visibleDemand.add(key);
                }
            }
            stream.visibleDemand.retainAll(wanted);
            stream.visibleSubscriptions.clear();
            stream.visibleSubscriptions.addAll(wanted);
            return;
        }
        if (stream.batch != request.batch()) return;
        for (long key : request.keys()) {
            if (stream.offered.remove(key) && LodSectionCursor.inWorldRange(key, player.chunkPosition(), stream.radius)) {
                stream.requests.addLast(key);
            }
        }
        stream.offered.clear();
        stream.sentOffer = null;
    }

    private static LodChannel.Offer offer(Stream stream, long[] keys, int total, boolean reconcile, long batch) {
        long[] offered = new long[total];
        var versions = new TileVersion[total];
        int count = 0;
        for (int i = 0; i < total; i++) {
            var version = stream.dimension.storage().wireVersion(keys[i]);
            if (version == null) {
                var tile = encodeTile(stream, keys[i]);
                if (tile == null && (!reconcile || stream.dimension.storage().revision(keys[i]) != 0)) continue;
                if (tile != null) version = tile.version();
            }
            offered[count] = keys[i];
            versions[count++] = version;
        }
        return new LodChannel.Offer(stream.scope, batch, Arrays.copyOf(offered, count), Arrays.copyOf(versions, count));
    }

    private static ArrayDeque<LodChannel.Section> encodeSections(Stream stream, long[] keys, int count) {
        var sections = new ArrayDeque<LodChannel.Section>(count);
        for (int index = 0; index < count; index++) {
            long key = keys[index];
            var tile = stream.dimension.storage().wireTile(key);
            if (tile == null) tile = encodeTile(stream, key);
            if (tile != null) sections.add(new LodChannel.Section(stream.scope, key, tile.version(), tile.data()));
        }
        return sections;
    }

    private static WireTile encodeTile(Stream stream, long key) {
        var stored = stream.dimension.storage().read(key);
        if (stored == null) return null;
        var palette = new RegistryLodPalette(stream.dimension.world().getMapper(), stream.level.registryAccess());
        try {
            var data = LodSectionCodec.encode(stored.section(), palette);
            var tile = new WireTile(new TileVersion(stored.number(), Hashing.sha256().hashBytes(data).asLong()), data);
            return stream.dimension.storage().cacheWireTile(key, tile) ? tile : null;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private <T> CompletableFuture<T> submit(java.util.function.Supplier<T> operation) {
        this.tasks.incrementAndGet();
        return CompletableFuture.supplyAsync(operation, this.worker)
                .whenComplete((value, error) -> this.tasks.decrementAndGet());
    }

    private static <T> T completed(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (RuntimeException ex) {
            Logger.warn("Voxy LOD stream job failed: " + ex);
            return null;
        }
    }
}
