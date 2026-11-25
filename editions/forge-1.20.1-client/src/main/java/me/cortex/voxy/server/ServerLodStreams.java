package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.common.network.LodSectionCodec;
import me.cortex.voxy.common.network.RegistryLodPalette;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final class Stream {
        final ServerLevel level;
        final ServerLodService.Dimension dimension;
        final long scope;
        final int radius;
        final Set<Long> offered = new HashSet<>();
        final ArrayDeque<Long> requests = new ArrayDeque<>();
        long bytes = PLAYER_BURST;
        long waitUntil;
        long nextPass;
        ChunkPos center;
        LodSectionCursor cursor;
        CompletableFuture<LodChannel.Offer> offer;
        CompletableFuture<LodChannel.Section> section;
        LodChannel.Section ready;

        Stream(ServerLevel level, ServerLodService.Dimension dimension, long scope, int radius) {
            this.level = level;
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
            if (stream == null || stream.level != player.serverLevel() || stream.radius != radius) {
                var dimension = this.service.dimension(player.serverLevel());
                stream = new Stream(player.serverLevel(), dimension, ++this.nextScope, radius);
                this.streams.put(player.getUUID(), stream);
                LodChannel.send(player, new LodChannel.Scope(this.service.worldId, stream.level.dimension().location(),
                        stream.scope, radius));
            }
            boolean mayQuery = lookups < MAX_METADATA_LOOKUPS_PER_TICK && this.tasks.get() < 32;
            if (this.sendNext(player, stream, now, mayQuery)) lookups++;
        }
        this.streams.keySet().retainAll(online);
    }

    private boolean sendNext(ServerPlayer player, Stream stream, long now, boolean mayQuery) {
        stream.bytes = Math.min(PLAYER_BURST, stream.bytes + PLAYER_BYTES_PER_SECOND / 20);
        var center = player.chunkPosition();
        if (!center.equals(stream.center) || stream.cursor == null && now >= stream.nextPass) {
            stream.center = center;
            stream.cursor = new LodSectionCursor(center, stream.radius,
                    stream.level.getMinBuildHeight(), stream.level.getMaxBuildHeight());
        }

        if (stream.ready == null && stream.section != null && stream.section.isDone()) {
            stream.ready = completed(stream.section);
            stream.section = null;
        }
        if (stream.ready != null) {
            if (!LodSectionCursor.inRange(stream.ready.key(), center, stream.radius)) {
                stream.ready = null;
            } else {
                int bytes = stream.ready.data().length + 32;
                if (bytes <= stream.bytes && bytes <= this.budget) {
                    LodChannel.send(player, stream.ready);
                    stream.bytes -= bytes;
                    this.budget -= bytes;
                    stream.ready = null;
                }
            }
        }
        if (stream.section == null && stream.ready == null && !stream.requests.isEmpty() && mayQuery) {
            long key = stream.requests.removeFirst();
            if (LodSectionCursor.inRange(key, center, stream.radius)) {
                stream.section = this.submit(() -> encodeSection(stream, key));
                return true;
            }
        }
        if (!stream.requests.isEmpty() || stream.ready != null || stream.section != null) return false;
        if (!stream.offered.isEmpty() && now < stream.waitUntil) return false;
        stream.offered.clear();

        if (stream.offer != null && stream.offer.isDone()) {
            var offer = completed(stream.offer);
            stream.offer = null;
            if (offer != null && offer.keys().length != 0) {
                for (long key : offer.keys()) {
                    stream.offered.add(key);
                }
                stream.waitUntil = now + 3_000_000_000L;
                LodChannel.send(player, offer);
                return false;
            }
        }

        if (stream.offer == null && stream.cursor != null && mayQuery) {
            long[] keys = new long[MAX_QUERY];
            int count = 0;
            while (count < keys.length && stream.cursor.hasNext()) {
                long key = stream.cursor.next();
                if (LodSectionCursor.inRange(key, center, stream.radius)) {
                    keys[count++] = key;
                }
            }
            if (!stream.cursor.hasNext()) {
                stream.cursor = null;
                stream.nextPass = now + 5_000_000_000L;
            }
            int total = count;
            if (total > 0) {
                stream.offer = this.submit(() -> offer(stream, keys, total));
                return true;
            }
        }
        return false;
    }

    void request(ServerPlayer player, LodChannel.Request request) {
        var stream = this.streams.get(player.getUUID());
        if (stream == null || stream.scope != request.scope() || stream.level != player.serverLevel()) return;
        for (long key : request.keys()) {
            if (stream.offered.remove(key) && LodSectionCursor.inRange(key, player.chunkPosition(), stream.radius)) {
                stream.requests.addLast(key);
            }
        }
        stream.offered.clear();
    }

    private static LodChannel.Offer offer(Stream stream, long[] keys, int total) {
        long[] offered = new long[total];
        long[] revisions = new long[total];
        int count = 0;
        for (int i = 0; i < total; i++) {
            long revision = stream.dimension.storage().revision(keys[i]);
            if (revision == 0) continue;
            offered[count] = keys[i];
            revisions[count++] = revision;
        }
        return new LodChannel.Offer(stream.scope, java.util.Arrays.copyOf(offered, count),
                java.util.Arrays.copyOf(revisions, count));
    }

    private static LodChannel.Section encodeSection(Stream stream, long key) {
        var stored = stream.dimension.storage().read(key);
        if (stored == null) return null;
        var palette = new RegistryLodPalette(stream.dimension.world().getMapper(), stream.level.registryAccess());
        try {
            return new LodChannel.Section(stream.scope, key, stored.number(), LodSectionCodec.encode(stored.section(), palette));
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
