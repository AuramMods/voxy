package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "voxy", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerLodService implements AutoCloseable {
    record Dimension(WorldEngine world, LodSectionStorage storage) {}

    private static ServerLodService current;

    private final MinecraftServer server;
    private final Path root;
    final UUID worldId;
    private final Map<ResourceKey<Level>, Dimension> worlds = new HashMap<>();
    private final ExecutorService worker;
    private final ServerLodGenerator generator;
    private final ServerLodStreams streams;
    private long lastReport = System.nanoTime();

    private ServerLodService(MinecraftServer server) throws IOException {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.ROOT);
        var path = Files.createDirectories(this.root.resolve("voxy-server")).resolve("world-id");
        if (Files.notExists(path)) Files.writeString(path, UUID.randomUUID().toString());
        this.worldId = UUID.fromString(Files.readString(path).trim());
        this.worker = Executors.newFixedThreadPool(ServerVoxyConfig.CHUNK_JOBS.get(), job -> {
            var thread = new Thread(job, "Voxy server storage");
            thread.setDaemon(true);
            return thread;
        });
        this.generator = new ServerLodGenerator(this, this.worker);
        this.streams = new ServerLodStreams(this, this.worker);
    }

    MinecraftServer server() {
        return this.server;
    }

    Dimension dimension(ServerLevel level) {
        return this.worlds.computeIfAbsent(level.dimension(), key -> {
            var directory = DimensionType.getStorageFolder(key, this.root).resolve("voxy-server");
            try {
                Files.createDirectories(directory);
                var storage = new LodSectionStorage(directory.resolve("lod.sqlite"));
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
        return current != null && current.server == server ? current : null;
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
        service.generator.tick();
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

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        var service = forServer(event.getServer());
        if (service == null) return;
        current = null;
        LodChannel.setServerHandler((player, request) -> {});
        service.close();
    }

    @Override
    public void close() {
        this.generator.close();
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
