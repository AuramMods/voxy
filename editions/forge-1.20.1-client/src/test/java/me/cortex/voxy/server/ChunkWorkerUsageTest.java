package me.cortex.voxy.server;

import net.minecraft.Util;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkWorkerUsageTest {
    @Test
    void countsChunkWorkOnlyWhileItRunsOnTheMinecraftPool() throws Exception {
        var minecraft = Util.backgroundExecutor();
        var withTracking = ChunkWorkerUsage.track(minecraft);
        assertTrue(ChunkWorkerUsage.sample().total() > 0);
        assertEquals(0, CompletableFuture.supplyAsync(() -> ChunkWorkerUsage.sample().busy(), minecraft)
                .get(5, TimeUnit.SECONDS));
        assertEquals(1, CompletableFuture.supplyAsync(() -> ChunkWorkerUsage.sample().busy(), withTracking)
                .get(5, TimeUnit.SECONDS));
        assertEquals(25, new ChunkWorkerUsage.Sample(2, 8).percent());
    }
}
