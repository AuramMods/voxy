package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
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

@Mod.EventBusSubscriber(modid = "voxy", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerLodService implements AutoCloseable {
    record Dimension(WorldEngine world, ServerSectionStorage storage) {}

    private static ServerLodService current;

    private final MinecraftServer server;
    private final Path root;
    final UUID worldId;
    private final Map<ResourceKey<Level>, Dimension> worlds = new HashMap<>();

    private ServerLodService(MinecraftServer server) throws IOException {
        this.server = server;
        this.root = server.getWorldPath(LevelResource.ROOT);
        var path = Files.createDirectories(this.root.resolve("voxy-server")).resolve("world-id");
        if (Files.notExists(path)) Files.writeString(path, UUID.randomUUID().toString());
        this.worldId = UUID.fromString(Files.readString(path).trim());
    }

    Dimension dimension(ServerLevel level) {
        return this.worlds.computeIfAbsent(level.dimension(), key -> {
            var directory = DimensionType.getStorageFolder(key, this.root).resolve("voxy-server");
            try {
                Files.createDirectories(directory);
                var storage = new ServerSectionStorage(directory.resolve("lod.sqlite"));
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
        Logger.info("Opened Voxy server LOD storage for " + service.worldId);
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        var service = forServer(event.getServer());
        if (service == null) return;
        current = null;
        service.close();
    }

    @Override
    public void close() {
        this.worlds.values().forEach(dimension -> dimension.world().free());
        this.worlds.clear();
    }
}
