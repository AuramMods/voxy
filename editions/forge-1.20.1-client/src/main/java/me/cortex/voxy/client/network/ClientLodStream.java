package me.cortex.voxy.client.network;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
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
import me.cortex.voxy.server.ServerLodService;
import me.cortex.voxy.server.ServerVoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "voxy", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientLodStream {
    private static volatile Context context;
    private static volatile LodChannel.Scope announced;
    private static ThreadPoolExecutor worker;
    private static long receivedBytes;
    private static long receivedTiles;
    private static long nextViewScan;
    private static long lastViewTiles;
    private static ViewSignature previousPlan;
    private static long[] previousView;

    private record Context(LodChannel.Scope scope, WorldIdentifier vanilla, WorldIdentifier remote,
                           RegistryAccess registries, WorldEngine world, LodSectionStorage storage) {}

    private record ViewSignature(int x, int y, int z, int yaw, int pitch, int width, int height,
                                 int projectionScale, float radius, float precision) {}

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
        previousPlan = null;
        previousView = null;
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
        receivedTiles = 0;
    }

    public static WorldIdentifier identity(WorldIdentifier vanilla) {
        var ready = context;
        if (ready != null && ready.vanilla.equals(vanilla)) return ready.remote;
        return vanilla;
    }

    public static boolean hasScope() {
        var scope = announced;
        var level = Minecraft.getInstance().level;
        return level != null && (isReadingLocalTerrain()
                || scope != null && scope.dimension().equals(level.dimension().location()));
    }

    public static boolean isReadingLocalTerrain() {
        var client = Minecraft.getInstance();
        return client.level != null && ServerLodService.hasLocalTerrain(client.getSingleplayerServer(),
                client.level.dimension());
    }

    public static float clampRadius(float blocks) {
        if (isReadingLocalTerrain()) {
            return Math.min(blocks, ServerVoxyConfig.RADIUS.get() * 16f);
        }
        var ready = announced;
        var level = Minecraft.getInstance().level;
        return ready == null || level == null || !ready.dimension().equals(level.dimension().location())
                ? blocks : Math.min(blocks, ready.radiusChunks() * 16f);
    }

    public static void prioritize(Viewport<?> viewport) {
        var ready = context;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        long now = System.nanoTime();
        if (ready == null || level == null || previousPlan != null && now < nextViewScan) return;
        nextViewScan = now + TimeUnit.MILLISECONDS.toNanos(500);
        var camera = minecraft.gameRenderer.getMainCamera();
        float radius = clampRadius(VoxyConfig.CONFIG.getLodRenderDistanceBlocks());
        var signature = new ViewSignature((int) Math.floor(viewport.cameraX), (int) Math.floor(viewport.cameraY),
                (int) Math.floor(viewport.cameraZ), Math.round(camera.getYRot() / 4), Math.round(camera.getXRot() / 4),
                viewport.width, viewport.height, Math.round(viewport.projection.m11() * 256), radius,
                VoxyConfig.CONFIG.subDivisionSize);
        if (lastViewTiles == receivedTiles && signature.equals(previousPlan)) return;
        previousPlan = signature;
        lastViewTiles = receivedTiles;
        var view = new ClientLodDemand(viewport, level.getMinBuildHeight(), level.getMaxBuildHeight(), radius);
        submit(ready, () -> {
            var wanted = view.plan(ready.storage);
            var keys = Arrays.copyOf(wanted, Math.min(wanted.length, LodChannel.MAX_OFFER));
            int pinned = Math.min(keys.length, LodChannel.MAX_OFFER / 4);
            int count = pinned;
            for (int i = pinned; i < wanted.length && count < keys.length; i++) {
                if (ready.storage.remoteTile(wanted[i]) == null) keys[count++] = wanted[i];
            }
            var demand = Arrays.copyOf(keys, count);
            Minecraft.getInstance().execute(() -> {
                if (context != ready || Arrays.equals(previousView, demand)) return;
                long batch = previousView == null ? LodChannel.VIEW_READY : LodChannel.VIEW_CHANGED;
                previousView = demand;
                LodChannel.request(new LodChannel.Request(ready.scope.scope(), batch, demand));
            });
        });
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        var pending = announced;
        var level = Minecraft.getInstance().level;
        if (event.phase == TickEvent.Phase.END && context == null && pending != null && level != null
                && pending.dimension().equals(level.dimension().location())) {
            begin(pending);
        }
    }

    private static void receive(LodChannel.ClientMessage message) {
        if (message instanceof LodChannel.Scope scope) {
            begin(scope);
        } else if (message instanceof LodChannel.Offer offer) {
            var ready = context;
            if (ready != null && ready.scope.scope() == offer.scope()) {
                submit(ready, () -> requestMissing(ready, offer));
            }
        } else if (message instanceof LodChannel.Section section) {
            var ready = context;
            if (ready != null && ready.scope.scope() == section.scope()) {
                receivedBytes += section.data().length;
                receivedTiles++;
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
        previousPlan = null;
        previousView = null;
        nextViewScan = 0;
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
            var version = offer.versions()[i];
            if (version == null) {
                var tile = ready.storage.remoteTile(key);
                if (tile != null && !ready.scope.session().equals(tile.session())) forget(ready, key);
            } else {
                available = true;
                if (ready.storage.needsRemote(key, ready.scope.session(), version)) keys[count++] = key;
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
        if (!ready.storage.needsRemote(message.key(), ready.scope.session(), message.version())) return;
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
        var target = ready.world.acquire(snapshot.key());
        try {
            synchronized (target) {
                byte previous = target.getNonEmptyChildren();
                byte drawable = drawableChildren(ready, snapshot.key(), children, previous);
                if (drawable != children) {
                    snapshot = new LodSectionSnapshot(snapshot.key(), drawable, snapshot.coverage(), snapshot.voxels());
                }
                if (ready.storage.commitRemote(snapshot, ready.scope.session(), message.version(), children)) {
                    snapshot.copyInto(target);
                    notifyMeshUpdate(ready.world, target, previous != drawable);
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
                    boolean hadChildren = target.getNonEmptyChildren() != 0;
                    new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.legacy(level),
                            new long[WorldSection.SECTION_VOLUME]).copyInto(target);
                    notifyMeshUpdate(ready.world, target, hadChildren);
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
        var section = ready.world.acquireIfExists(key);
        if (section == null) return;
        try {
            synchronized (section) {
                byte previous = section.getNonEmptyChildren();
                byte children = drawableChildren(ready, key, tile.children(), previous);
                if (previous != children) {
                    section._unsafeSetNonEmptyChildren(children);
                    ready.storage.saveSection(section);
                    ready.world.markDirty(section, WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT
                            | WorldEngine.UPDATE_TYPE_DONT_SAVE, 0);
                }
            }
        } finally {
            section.release();
        }
    }

    private static byte drawableChildren(Context ready, long key, byte advertised, byte previous) {
        int lod = WorldEngine.getLevel(key) - 1;
        if (lod < 0 || advertised == 0) return advertised;
        int baseX = WorldEngine.getX(key) * 2;
        int baseY = WorldEngine.getY(key) * 2;
        int baseZ = WorldEngine.getZ(key) * 2;
        int available = 0;
        for (int child = 0; child < 8; child++) {
            if ((advertised & 1 << child) == 0) continue;
            long childKey = WorldEngine.getWorldSectionId(lod, baseX + (child & 1),
                    baseY + ((child >> 2) & 1), baseZ + ((child >> 1) & 1));
            if (ready.storage.remoteTile(childKey) != null) available |= 1 << child;
        }
        return selectDrawableChildren(previous, advertised, (byte) available);
    }

    static byte selectDrawableChildren(byte previous, byte advertised, byte available) {
        // A cold parent waits for full coverage. A parent already split keeps every usable child.
        return (available & advertised) == advertised ? advertised : (byte) (previous & advertised & available);
    }

    private static void notifyMeshUpdate(WorldEngine world, WorldSection section, boolean childrenChanged) {
        int flags = WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_DONT_SAVE;
        if (childrenChanged) flags |= WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT;
        world.markDirty(section, flags, WorldEngine.ALL_MESH_NEIGHBORS);
    }
}
