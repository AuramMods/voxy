package me.cortex.voxy.server;

import com.mojang.datafixers.util.Either;
import me.cortex.voxy.commonImpl.mixin.minecraft.AccessorServerChunkCache;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Main-thread lease. Lowering the ticket removes only the Voxy request. */
final class ServerChunkTicket implements AutoCloseable {
    private static final TicketType<ChunkPos> TYPE = TicketType.create("voxy_lod",
            Comparator.comparingLong(ChunkPos::toLong));
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(30);

    private final ServerChunkCache cache;
    private final ChunkPos position;
    private final long started = System.nanoTime();
    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
    private boolean closed;

    ServerChunkTicket(ServerChunkCache cache, ChunkPos position) {
        this.cache = cache;
        this.position = position;
        this.cache.addRegionTicket(TYPE, position, 0, position);
    }

    LevelChunk poll() {
        if (this.closed) throw new IllegalStateException("Polling a released Voxy chunk ticket");
        if (System.nanoTime() - this.started >= TIMEOUT) {
            throw new IllegalStateException("Voxy chunk ticket timed out at " + this.position);
        }
        if (this.future == null) {
            // Ticket propagation can take a tick. The public accessor waits when called on this thread.
            var pending = ((AccessorServerChunkCache) this.cache).voxy$pollChunk(
                    this.position.x, this.position.z, ChunkStatus.FULL, false);
            if (pending == ChunkHolder.UNLOADED_CHUNK_FUTURE) return null;
            this.future = pending;
        }
        var result = this.future.getNow(null);
        if (result == null) return null;
        var chunk = result.left().orElseThrow(() ->
                new IllegalStateException("Voxy could not load " + this.position + ": " + result.right().orElseThrow()));
        var levelChunk = chunk instanceof ImposterProtoChunk wrapped ? wrapped.getWrapped() : (LevelChunk) chunk;
        return levelChunk.isLightCorrect() ? levelChunk : null;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.cache.removeRegionTicket(TYPE, this.position, 0, this.position);
        }
    }
}
