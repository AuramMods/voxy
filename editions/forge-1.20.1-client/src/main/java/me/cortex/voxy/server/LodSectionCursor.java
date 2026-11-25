package me.cortex.voxy.server;

import it.unimi.dsi.fastutil.longs.LongArrays;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

final class LodSectionCursor {
    private static final int FULL_DETAIL_RADIUS = 12;
    private final ChunkPos center;
    private final int radius;
    private final int minSection;
    private final int maxSection;
    private int level = WorldEngine.MAX_LOD_LAYER;
    private NearChunkCursor columns;
    private int y;

    LodSectionCursor(ChunkPos center, int radius, int minBlockY, int maxBlockY) {
        this.center = center;
        this.radius = radius;
        this.minSection = minBlockY >> 5;
        this.maxSection = (maxBlockY - 1) >> 5;
        this.resetLevel();
    }

    private void resetLevel() {
        int side = 2 << this.level;
        int range = detailRadius(this.level, this.radius);
        this.columns = new NearChunkCursor(new ChunkPos(Math.floorDiv(this.center.x, side),
                Math.floorDiv(this.center.z, side)), (range + side - 1) / side + 2);
        this.y = this.minSection >> this.level;
    }

    boolean hasNext() {
        return this.level >= 0;
    }

    long next() {
        var column = this.columns.peek().position();
        long key = WorldEngine.getWorldSectionId(this.level, column.x, this.y, column.z);
        if (++this.y > this.maxSection >> this.level) {
            this.columns.advance();
            this.y = this.minSection >> this.level;
            if (this.columns.peek() == null && --this.level >= 0) this.resetLevel();
        }
        return key;
    }

    static boolean inRange(long key, ChunkPos center, int radius) {
        return inWorldRange(key, center, detailRadius(WorldEngine.getLevel(key), radius));
    }

    static boolean inWorldRange(long key, ChunkPos center, int radius) {
        int level = WorldEngine.getLevel(key);
        if (level > WorldEngine.MAX_LOD_LAYER || (key & 15) != 0) return false;
        int side = 2 << level;
        long minX = (long) WorldEngine.getX(key) * side;
        long minZ = (long) WorldEngine.getZ(key) * side;
        long x = Math.max(0, Math.max(minX - center.x, center.x - (minX + side - 1)));
        long z = Math.max(0, Math.max(minZ - center.z, center.z - (minZ + side - 1)));
        return x*x + z*z <= (long) radius * radius;
    }

    private static int detailRadius(int level, int radius) {
        return level == WorldEngine.MAX_LOD_LAYER ? radius : Math.min(radius, FULL_DETAIL_RADIUS << level);
    }

    static void preferVisible(long[] keys, int from, int to, ChunkPos center, Vec3 forward) {
        LongArrays.stableSort(keys, from, to, (first, second) -> {
            int level = WorldEngine.getLevel(first);
            int coarser = Integer.compare(WorldEngine.getLevel(second), level);
            if (coarser != 0 || level == WorldEngine.MAX_LOD_LAYER) return coarser;
            return Boolean.compare(ahead(second, level, center, forward), ahead(first, level, center, forward));
        });
    }

    private static boolean ahead(long key, int level, ChunkPos center, Vec3 forward) {
        int size = 2 << level;
        double x = (WorldEngine.getX(key) + 0.5) * size - (center.x + 0.5);
        double z = (WorldEngine.getZ(key) + 0.5) * size - (center.z + 0.5);
        return x * forward.x + z * forward.z >= 0;
    }
}
