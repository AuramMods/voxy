package me.cortex.voxy.server;

import com.electronwill.nightconfig.core.CommentedConfig;
import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Mod.EventBusSubscriber(modid = "voxy", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerLodService implements AutoCloseable {
    record Dimension(WorldEngine world, LodSectionStorage storage) {}

    private static volatile ServerLodService current;

    private final MinecraftServer server;
    private final Path root;
    final UUID worldId;
    private final Map<ResourceKey<Level>, Dimension> worlds = new ConcurrentHashMap<>();
    private final AtomicReference<LocalLodTerrain> localTerrain = new AtomicReference<>();
    private final ThreadPoolExecutor worker;
    private final ServerLodGenerator generator;
    private final ServerLodEdits edits;
    private final ServerLodStreams streams;
    private long lastReport = System.nanoTime();

    private ServerLodService(MinecraftServer server) throws IOException {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.ROOT);
        var path = Files.createDirectories(this.root.resolve("voxy-server")).resolve("world-id");
        if (Files.notExists(path)) Files.writeString(path, UUID.randomUUID().toString());
        this.worldId = UUID.fromString(Files.readString(path).trim());
        int jobs = ServerVoxyConfig.chunkJobs(false);
        this.worker = new ThreadPoolExecutor(jobs, jobs, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), job -> {
            var thread = new Thread(job, "Voxy server storage");
            thread.setDaemon(true);
            return thread;
        });
        this.generator = new ServerLodGenerator(this, this.worker);
        this.edits = new ServerLodEdits(this, this.worker);
        this.streams = new ServerLodStreams(this, this.worker);
    }

    MinecraftServer server() {
        return this.server;
    }

    Dimension dimension(ServerLevel level) {
        return this.dimension(level.dimension());
    }

    private Path storagePath(ResourceKey<Level> key) {
        return DimensionType.getStorageFolder(key, this.root).resolve("voxy-server").resolve("lod.sqlite");
    }

    private Dimension dimension(ResourceKey<Level> dimension) {
        return this.worlds.computeIfAbsent(dimension, key -> {
            var path = this.storagePath(key);
            try {
                Files.createDirectories(path.getParent());
                var storage = new LodSectionStorage(path);
                try {
                    return new Dimension(new WorldEngine(storage), storage);
                } catch (RuntimeException | Error ex) {
                    storage.close();
                    throw ex;
                }
            } catch (SQLException | ClassNotFoundException | IOException ex) {
                throw new IllegalStateException("Opening Voxy LOD storage for " + key.location(), ex);
            }
        });
    }

    static ServerLodService forServer(MinecraftServer server) {
        var service = current;
        return service != null && service.server == server ? service : null;
    }

    public static LocalLodTerrain openLocalTerrain(MinecraftServer server, ResourceKey<Level> key) {
        var service = forServer(server);
        if (service == null || server.isDedicatedServer()) return null;
        synchronized (service) {
            if (forServer(server) != service) return null;
            return service.openLocalTerrain(key);
        }
    }

    public static boolean hasLocalTerrain(MinecraftServer server, ResourceKey<Level> key) {
        var service = forServer(server);
        var terrain = service == null ? null : service.localTerrain.get();
        return terrain != null && terrain.dimension().equals(key);
    }

    private LocalLodTerrain openLocalTerrain(ResourceKey<Level> key) {
        var dimension = this.dimension(key);
        try {
            var terrain = new LocalLodTerrain(key, LodSectionStorage.openReadOnly(this.storagePath(key)),
                    dimension.world().getMapper(), this.localTerrain);
            this.localTerrain.set(terrain);
            Logger.info("Reading Voxy LODs directly from the local save for " + key.location());
            return terrain;
        } catch (SQLException failure) {
            Logger.warn("Could not open local Voxy LOD terrain for " + key.location(), failure);
            return null;
        }
    }

    public static void applyLocalConfig(MinecraftServer server, CommentedConfig config) {
        if (server.isDedicatedServer()) return;
        server.execute(() -> {
            if (forServer(server) != null) ServerVoxyConfig.apply(config);
        });
    }

    public static void blockChanged(ServerLevel level, BlockPos position) {
        var service = forServer(level.getServer());
        if (service != null && level.getServer().isSameThread()) {
            service.edits.blockChanged(level, new ChunkPos(position));
        }
    }

    public static void lightChanged(ServerLevel level, long position) {
        var service = forServer(level.getServer());
        if (service != null) service.edits.lightChanged(level, position);
    }

    boolean pendingEdit(ServerLevel level, long position) {
        return this.edits.isPending(level, position);
    }

    void publish(ServerLevel level, long[] changed) {
        this.streams.publish(level, changed);
        this.publishLocal(level, changed);
    }

    void publishLocal(ServerLevel level, long[] changed) {
        var terrain = this.localTerrain.get();
        if (terrain != null && terrain.dimension().equals(level.dimension()) && changed.length != 0) {
            this.worker.execute(() -> terrain.refresh(changed));
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && level.getServer().isSameThread()) {
            var service = forServer(level.getServer());
            if (service != null) service.edits.unloading(level, chunk);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var service = forServer(level.getServer());
            if (service != null) service.generator.unload(level);
        }
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) throws IOException {
        var server = event.getServer();
        var service = new ServerLodService(server);
        try {
            service.dimension(server.overworld());
        } catch (RuntimeException | Error ex) {
            service.close();
            throw ex;
        }
        current = service;
        LodChannel.setServerHandler(service.streams::request);
        Logger.info("Opened Voxy server LOD storage for " + service.worldId);
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent event) {
        var service = forServer(event.getServer());
        if (service == null || event.phase != TickEvent.Phase.END) return;
        service.edits.tick(event.getServer().getTickCount());
        boolean playersOnline = !event.getServer().getPlayerList().getPlayers().isEmpty();
        int jobs = ServerVoxyConfig.chunkJobs(playersOnline);
        service.resizeWorkers(jobs);
        service.generator.tick(playersOnline, jobs);
        service.streams.tick();
        long now = System.nanoTime();
        long elapsed = now - service.lastReport;
        if (elapsed >= TimeUnit.SECONDS.toNanos(60)) {
            var progress = service.generator.report();
            double perSecond = (progress.generated() + progress.imported()) * 1_000_000_000.0 / elapsed;
            Logger.info(String.format(java.util.Locale.ROOT,
                    "Voxy LODs %.2f chunks/s (%d generated, %d reused), generating %s, %d active tickets",
                    perSecond, progress.generated(), progress.imported(),
                    progress.generating().isEmpty() ? "idle" : progress.generating(), progress.tickets()));
            service.lastReport = now;
        }
    }

    private void resizeWorkers(int jobs) {
        int current = this.worker.getMaximumPoolSize();
        if (current == jobs) return;
        if (jobs > current) this.worker.setMaximumPoolSize(jobs);
        this.worker.setCorePoolSize(jobs);
        if (jobs < current) this.worker.setMaximumPoolSize(jobs);
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        var service = forServer(event.getServer());
        if (service == null) return;
        synchronized (service) {
            current = null;
        }
        LodChannel.setServerHandler((player, request) -> {});
        service.close();
    }

    @Override
    public void close() {
        this.generator.close();
        this.edits.flush(this.server.getTickCount());
        this.worker.shutdown();
        try {
            if (!this.worker.awaitTermination(10, TimeUnit.SECONDS)) {
                this.worker.shutdownNow();
                if (!this.worker.awaitTermination(10, TimeUnit.SECONDS)) {
                    Logger.warn("Voxy storage workers are still shutting down");
                    return;
                }
            }
        } catch (InterruptedException ex) {
            this.worker.shutdownNow();
            Thread.currentThread().interrupt();
            return;
        }
        this.worlds.values().forEach(dimension -> dimension.world().free());
        this.worlds.clear();
    }
}
