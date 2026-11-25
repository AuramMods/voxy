package me.cortex.voxy.client.network;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.common.world.WorldEngine;
import org.joml.FrustumIntersection;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Estimates the same projected-size subdivision the renderer uses, before the meshes are available. */
final class ClientLodDemand {
    private static final int MAX_ROOT_RADIUS = 12;
    private static final int MAX_VISITS = 384;
    private static final int MAX_CANDIDATES = LodChannel.MAX_OFFER * 3;

    private record Tile(int level, int x, int y, int z, double distanceSquared) {
        long key() {
            return WorldEngine.getWorldSectionId(this.level, this.x, this.y, this.z);
        }
    }

    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final FrustumIntersection frustum;
    private final int minY;
    private final int maxY;
    private final float radiusBlocks;
    private final double radiusSquared;
    private final double projectionArea;
    private final double subdivisionArea;
    private final PriorityQueue<Tile> visible = new PriorityQueue<>(Comparator.comparingDouble(Tile::distanceSquared)
            .thenComparingLong(Tile::key));

    ClientLodDemand(Viewport<?> view, int minY, int maxY, float radiusBlocks) {
        this.cameraX = view.cameraX;
        this.cameraY = view.cameraY;
        this.cameraZ = view.cameraZ;
        this.frustum = new FrustumIntersection(view.MVP);
        this.minY = minY;
        this.maxY = maxY;
        this.radiusBlocks = radiusBlocks;
        this.radiusSquared = (double) radiusBlocks * radiusBlocks;
        this.projectionArea = Math.abs((double) view.projection.m00() * view.projection.m11())
                * view.width * view.height / 4;
        this.subdivisionArea = Math.pow(VoxyConfig.CONFIG.subDivisionSize, 2);
    }

    long[] plan(LodSectionStorage storage) {
        int rootLevel = WorldEngine.MAX_LOD_LAYER;
        int rootSize = 32 << rootLevel;
        int xCenter = (int) Math.floor(this.cameraX / rootSize);
        int zCenter = (int) Math.floor(this.cameraZ / rootSize);
        int radius = Math.min(MAX_ROOT_RADIUS, (int) Math.ceil(this.radiusBlocks / rootSize) + 1);
        for (int z = zCenter - radius; z <= zCenter + radius; z++) {
            for (int x = xCenter - radius; x <= xCenter + radius; x++) {
                for (int y = Math.floorDiv(this.minY, rootSize); y <= Math.floorDiv(this.maxY - 1, rootSize); y++) {
                    this.enqueue(rootLevel, x, y, z);
                }
            }
        }

        var wanted = new LongLinkedOpenHashSet();
        for (int i = 0; i < MAX_VISITS && !this.visible.isEmpty() && wanted.size() < MAX_CANDIDATES; i++) {
            var tile = this.visible.remove();
            int size = 32 << tile.level;
            double area = this.projectionArea * size * size / Math.max(1, tile.distanceSquared);
            var cached = storage.remoteTile(tile.key());
            if (tile.level > 0 && area > this.subdivisionArea && cached != null && cached.children() != 0) {
                for (int child = 0; child < 8; child++) {
                    if ((cached.children() & 1 << child) == 0) continue;
                    this.enqueue(tile.level - 1, tile.x * 2 + (child & 1), tile.y * 2 + (child >> 2),
                            tile.z * 2 + ((child >> 1) & 1));
                }
                continue;
            }
            // Ancestors let the renderer reach the requested tile and provide geometry while it loads.
            for (int level = rootLevel; level >= tile.level && wanted.size() < MAX_CANDIDATES; level--) {
                int shift = level - tile.level;
                int x = tile.x >> shift;
                int y = tile.y >> shift;
                int z = tile.z >> shift;
                wanted.add(WorldEngine.getWorldSectionId(level, x, y, z));
                if (level < rootLevel) {
                    var parent = storage.remoteTile(WorldEngine.getWorldSectionId(level + 1, x >> 1, y >> 1, z >> 1));
                    if (parent != null) this.addSiblings(wanted, level, x & ~1, y & ~1, z & ~1, parent.children());
                }
            }
        }
        return wanted.toLongArray();
    }

    private void addSiblings(LongLinkedOpenHashSet wanted, int level, int x, int y, int z, byte children) {
        int size = 32 << level;
        // The renderer keeps the parent until its children are ready to replace it as a group.
        for (int child = 0; child < 8 && wanted.size() < MAX_CANDIDATES; child++) {
            int childY = y + (child >> 2);
            if ((children & 1 << child) != 0 && childY * size < this.maxY && (childY + 1) * size > this.minY) {
                wanted.add(WorldEngine.getWorldSectionId(level, x + (child & 1), childY, z + ((child >> 1) & 1)));
            }
        }
    }

    private void enqueue(int level, int x, int y, int z) {
        int size = 32 << level;
        int bottom = Math.max(this.minY, y * size);
        int top = Math.min(this.maxY, (y + 1) * size);
        if (bottom >= top) return;
        float dx = (float) (x * (double) size - this.cameraX);
        float dz = (float) (z * (double) size - this.cameraZ);
        float dy = (float) (bottom - this.cameraY);
        float ymax = (float) (top - this.cameraY);
        double range = distanceToAxis(dx, dx + size) + distanceToAxis(dz, dz + size);
        if (range <= this.radiusSquared && this.frustum.testAab(dx, dy, dz, dx + size, ymax, dz + size)) {
            this.visible.add(new Tile(level, x, y, z, range + distanceToAxis(dy, ymax)));
        }
    }

    private static double distanceToAxis(float from, float to) {
        double distance = Math.max(0, Math.max(from, -to));
        return distance * distance;
    }
}
