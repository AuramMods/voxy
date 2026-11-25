package me.cortex.voxy.server;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.ChunkPos;

final class LodSectionCursor {
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
        this.columns = new NearChunkCursor(new ChunkPos(Math.floorDiv(this.center.x, side),
                Math.floorDiv(this.center.z, side)), (this.radius + side - 1) / side + 2);
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
        int level = WorldEngine.getLevel(key);
        if (level > WorldEngine.MAX_LOD_LAYER || (key & 15) != 0) return false;
        int side = 2 << level;
        long minX = (long) WorldEngine.getX(key) * side;
        long minZ = (long) WorldEngine.getZ(key) * side;
        long x = Math.max(0, Math.max(minX - center.x, center.x - (minX + side - 1)));
        long z = Math.max(0, Math.max(minZ - center.z, center.z - (minZ + side - 1)));
        return x*x + z*z <= (long) radius * radius;
    }
}
