package me.cortex.voxy.server;

import net.minecraft.Util;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/** Active Minecraft background workers executing chunk generation, lighting, or load preparation. */
public final class ChunkWorkerUsage {
    private static final AtomicInteger BUSY = new AtomicInteger();
    private static final ThreadLocal<Boolean> IN_CHUNK_TASK = new ThreadLocal<>();

    public record Sample(int busy, int total) {
        public int percent() {
            return Math.round(100.0f * this.busy / this.total);
        }
    }

    private ChunkWorkerUsage() {}

    public static Executor track(Executor executor) {
        if (executor != Util.backgroundExecutor()) return executor;
        return task -> executor.execute(() -> {
            if (IN_CHUNK_TASK.get() != null) {
                task.run();
                return;
            }
            IN_CHUNK_TASK.set(true);
            BUSY.incrementAndGet();
            try {
                task.run();
            } finally {
                BUSY.decrementAndGet();
                IN_CHUNK_TASK.remove();
            }
        });
    }

    public static Sample sample() {
        Executor executor = Util.backgroundExecutor();
        int total;
        if (executor instanceof ForkJoinPool pool) {
            total = pool.getParallelism();
        } else if (executor instanceof ThreadPoolExecutor pool && pool.getMaximumPoolSize() != Integer.MAX_VALUE) {
            total = pool.getMaximumPoolSize();
        } else {
            return null;
        }
        return new Sample(Math.min(BUSY.get(), total), total);
    }
}
