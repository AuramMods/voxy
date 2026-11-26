package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.network.ClientLodStream;
import me.cortex.voxy.server.ChunkWorkerUsage;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "voxy", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientDebugStats {
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long MIN_SAMPLE_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(40);
    private static final ArrayDeque<Observation> history = new ArrayDeque<>();
    private static Snapshot latest;

    private record Observation(long time, int busyWorkers, long busyWorkerNanos, long meshes, long receivedSections) {}

    public record Snapshot(double busyWorkers, int totalWorkers, int queuedMeshJobs, double meshesPerSecond,
                           double receivedSectionsPerSecond, boolean meshPacingBypassed, boolean localTerrain, boolean remoteStream) {}

    private ClientDebugStats() {}

    public static Snapshot latest() {
        return latest;
    }

    static void reset() {
        history.clear();
        latest = null;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        var client = Minecraft.getInstance();
        if (event.phase != TickEvent.Phase.END || client.level == null) return;

        long now = System.nanoTime();
        var previous = history.peekLast();
        if (previous != null && now - previous.time() < MIN_SAMPLE_INTERVAL_NANOS) return;

        var workers = client.getSingleplayerServer() == null ? null : ChunkWorkerUsage.sample();
        int busyWorkers = workers == null ? 0 : workers.busy();
        int totalWorkers = workers == null ? 0 : workers.total();
        long busyWorkerNanos = previous == null ? 0
                : previous.busyWorkerNanos() + (now - previous.time()) * previous.busyWorkers();
        long meshes = RenderGenerationService.getCompletedMeshCount();
        long receivedSections = ClientLodStream.getReceivedSectionCount();
        history.addLast(new Observation(now, busyWorkers, busyWorkerNanos, meshes, receivedSections));

        // Keep the observation just before the window so we can prorate its worker time.
        long cutoff = now - WINDOW_NANOS;
        Observation boundary = null;
        while (history.getFirst().time() < cutoff) {
            boundary = history.removeFirst();
        }
        if (boundary != null) history.addFirst(boundary);
        var first = history.getFirst();
        long windowStart = Math.max(cutoff, first.time());
        long workerElapsed = now - windowStart;
        long workerNanosAtStart = first.busyWorkerNanos() + (windowStart - first.time()) * first.busyWorkers();
        double averageBusyWorkers = workerElapsed == 0 ? busyWorkers
                : (double) (busyWorkerNanos - workerNanosAtStart) / workerElapsed;

        // Counters are read at tick boundaries. Use the elapsed time between those reads.
        long counterElapsed = now - first.time();
        double rate = counterElapsed == 0 ? 0 : (double) TimeUnit.SECONDS.toNanos(1) / counterElapsed;
        var renderer = client.levelRenderer == null ? null
                : ((IGetVoxyRenderSystem) client.levelRenderer).voxy$getRenderSystem();
        boolean localTerrain = renderer != null && ClientLodStream.isReadingLocalTerrain();
        latest = new Snapshot(averageBusyWorkers, totalWorkers, renderer == null ? -1 : renderer.getQueuedMeshJobs(),
                (meshes - first.meshes()) * rate, (receivedSections - first.receivedSections()) * rate,
                renderer != null && renderer.isMeshPacingBypassed(), localTerrain, renderer != null && !localTerrain && ClientLodStream.hasScope());
    }
}
