package me.cortex.voxy.client.network;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.common.network.LodSectionCodec;
import me.cortex.voxy.common.network.RegistryLodPalette;
import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ClientLodStream {
    private static final int ALL_MESH_NEIGHBORS = 0x3f | (0xf << 6) | (0xf << 10) | (0xf << 14) | (3 << 18);
    private static volatile Context context;
    private static volatile LodChannel.Scope announced;
    private static ThreadPoolExecutor worker;
    private static long receivedBytes;

    private record Context(LodChannel.Scope scope, WorldIdentifier vanilla, WorldIdentifier remote,
                           RegistryAccess registries, WorldEngine world, LodSectionStorage storage) {}

    private ClientLodStream() {}

    public static void open() {
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), task -> {
            var thread = new Thread(task, "Voxy multiplayer LODs");
            thread.setDaemon(true);
            return thread;
        });
        LodChannel.setClientHandler(ClientLodStream::receive);
    }

    public static void close() {
        announced = null;
        context = null;
        LodChannel.setClientHandler(message -> {});
        if (worker != null) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    Logger.warn("Voxy multiplayer cache worker has pending work at logout");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        Logger.info("Voxy LOD session received " + receivedBytes + " compressed bytes");
        receivedBytes = 0;
    }

    public static WorldIdentifier identity(WorldIdentifier vanilla) {
        var ready = context;
        if (ready != null && ready.vanilla.equals(vanilla)) return ready.remote;
        return vanilla;
    }

    public static boolean hasScope() {
        var scope = announced;
        var level = Minecraft.getInstance().level;
        return scope != null && level != null && scope.dimension().equals(level.dimension().location());
    }

    public static float clampRadius(float blocks) {
        var ready = announced;
        var level = Minecraft.getInstance().level;
        return ready == null || level == null || !ready.dimension().equals(level.dimension().location())
                ? blocks : Math.min(blocks, ready.radiusChunks() * 16f);
    }

    private static void receive(LodChannel.ClientMessage message) {
        if (message instanceof LodChannel.Scope scope) {
            begin(scope);
        } else if (message instanceof LodChannel.Offer offer) {
            var ready = context;
            if (ready != null && ready.scope.scope() == offer.scope()) {
                submit(ready, () -> requestMissing(ready, offer));
            } else if (announced != null && announced.scope() == offer.scope()) {
                begin(announced);
                var retry = context;
                if (retry != null) submit(retry, () -> requestMissing(retry, offer));
            }
        } else if (message instanceof LodChannel.Section section) {
            var ready = context;
            if (ready != null && ready.scope.scope() == section.scope()) {
                receivedBytes += section.data().length;
                submit(ready, () -> accept(ready, section));
            }
        }
    }

    private static void begin(LodChannel.Scope scope) {
        if (scope.radiusChunks() < 1 || scope.radiusChunks() > 2048) return;
        announced = scope;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (worker == null || level == null || !scope.dimension().equals(level.dimension().location())) {
            context = null;
            return;
        }
        var vanilla = WorldIdentifier.of(level);
        if (vanilla == null) return;
        var remote = new WorldIdentifier(vanilla.key, vanilla.biomeSeed, vanilla.dimension, scope.world());
        var world = remote.getOrCreateEngine();
        if (world == null || !(world.storage instanceof LodSectionStorage storage)) return;
        var previous = context;
        context = new Context(scope, vanilla, remote, level.registryAccess(), world, storage);
        if (previous == null || previous.world != world || previous.scope.radiusChunks() != scope.radiusChunks()) {
            var renderer = (IGetVoxyRenderSystem) minecraft.levelRenderer;
            renderer.voxy$shutdownRenderer();
            renderer.voxy$createRenderer();
            Logger.info("Opened Voxy multiplayer LOD stream for " + scope.dimension());
        }
    }

    private static void submit(Context ready, Runnable task) {
        var pool = worker;
        if (pool == null) return;
        ready.world.acquireRef();
        try {
            pool.execute(() -> {
                try {
                    if (context == ready) task.run();
                } catch (RuntimeException error) {
                    Logger.warn("Voxy multiplayer cache job failed: " + error);
                } finally {
                    ready.world.releaseRef();
                }
            });
        } catch (RejectedExecutionException ex) {
            ready.world.releaseRef();
        }
    }

    private static void requestMissing(Context ready, LodChannel.Offer offer) {
        var keys = new long[offer.keys().length];
        int count = 0;
        boolean available = false;
        for (int i = 0; i < keys.length; i++) {
            if (context != ready) return;
            long key = offer.keys()[i];
            var tile = ready.storage.remoteTile(key);
            var version = offer.versions()[i];
            if (version == null) {
                if (tile != null && !ready.scope.session().equals(tile.session())) forget(ready, key);
            } else {
                available = true;
                if (tile == null || tile.needs(ready.scope.session(), version)) keys[count++] = key;
            }
        }
        if (!available) return;
        var request = new LodChannel.Request(offer.scope(), offer.batch(), Arrays.copyOf(keys, count));
        Minecraft.getInstance().execute(() -> {
            if (context == ready) LodChannel.request(request);
        });
    }

    private static void accept(Context ready, LodChannel.Section message) {
        if (message.version() == null || message.version().revision() < 1) return;
        var existing = ready.storage.remoteTile(message.key());
        if (existing != null && !existing.needs(ready.scope.session(), message.version())) return;
        var palette = new RegistryLodPalette(ready.world.getMapper(), ready.registries);
        LodSectionSnapshot snapshot;
        try {
            snapshot = LodSectionCodec.decode(message.key(), message.data(), palette);
        } catch (IOException failure) {
            Logger.warn("Discarded invalid Voxy section: " + failure.getMessage());
            return;
        }
        if (context != ready) return;
        int level = WorldEngine.getLevel(snapshot.key());
        byte children = snapshot.children();
        if (level > 0 && !allChildrenReady(ready, snapshot.key(), children)) {
            snapshot = new LodSectionSnapshot(snapshot.key(), (byte) 0, snapshot.coverage(), snapshot.voxels());
        }
        var target = ready.world.acquire(snapshot.key());
        try {
            synchronized (target) {
                if (ready.storage.commitRemote(snapshot, ready.scope.session(), message.version(), children)) {
                    snapshot.copyInto(target);
                    notifyRenderer(ready.world, target);
                }
            }
        } finally {
            target.release();
        }
        if (level < WorldEngine.MAX_LOD_LAYER) {
            refreshParent(ready, parent(snapshot.key()));
        }
    }

    private static void forget(Context ready, long key) {
        if (!ready.storage.forgetRemote(key, ready.scope.session())) return;
        int level = WorldEngine.getLevel(key);
        var target = ready.world.acquireIfExists(key);
        if (target != null) {
            try {
                synchronized (target) {
                    new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.legacy(level),
                            new long[WorldSection.SECTION_VOLUME]).copyInto(target);
                    notifyRenderer(ready.world, target);
                }
            } finally {
                target.release(WorldSection.RELEASE_HINT_DONT_CACHE);
            }
        }
        if (level < WorldEngine.MAX_LOD_LAYER) refreshParent(ready, parent(key));
    }

    private static long parent(long key) {
        return WorldEngine.getWorldSectionId(WorldEngine.getLevel(key) + 1, WorldEngine.getX(key) >> 1,
                WorldEngine.getY(key) >> 1, WorldEngine.getZ(key) >> 1);
    }

    private static void refreshParent(Context ready, long key) {
        var tile = ready.storage.remoteTile(key);
        if (tile == null) return;
        byte children = allChildrenReady(ready, key, tile.children()) ? tile.children() : 0;
        var section = ready.world.acquireIfExists(key);
        if (section == null) return;
        try {
            synchronized (section) {
                if (section.getNonEmptyChildren() != children) {
                    section._unsafeSetNonEmptyChildren(children);
                    ready.storage.saveSection(section);
                    notifyRenderer(ready.world, section);
                }
            }
        } finally {
            section.release();
        }
    }

    private static boolean allChildrenReady(Context ready, long key, byte children) {
        int lod = WorldEngine.getLevel(key) - 1;
        if (lod < 0) return true;
        int baseX = WorldEngine.getX(key) * 2;
        int baseY = WorldEngine.getY(key) * 2;
        int baseZ = WorldEngine.getZ(key) * 2;
        for (int child = 0; child < 8; child++) {
            if ((children & 1 << child) == 0) continue;
            long childKey = WorldEngine.getWorldSectionId(lod, baseX + (child & 1),
                    baseY + ((child >> 2) & 1), baseZ + ((child >> 1) & 1));
            if (ready.storage.remoteTile(childKey) == null) return false;
        }
        return true;
    }

    private static void notifyRenderer(WorldEngine world, WorldSection section) {
        world.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS | WorldEngine.UPDATE_TYPE_DONT_SAVE,
                ALL_MESH_NEIGHBORS);
    }
}
