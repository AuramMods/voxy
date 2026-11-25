package me.cortex.voxy.server;

import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class NearChunkCursorTest {
    @Test
    void keepsCoarseTilesFirstAndPrefersTheViewForRefinement() {
        long behind = WorldEngine.getWorldSectionId(1, -1, 0, 0);
        long ahead = WorldEngine.getWorldSectionId(1, 1, 0, 0);
        long coarse = WorldEngine.getWorldSectionId(4, -1, 0, 0);
        long[] keys = {behind, ahead, coarse};
        LodSectionCursor.preferVisible(keys, 0, keys.length, new ChunkPos(0, 0), new Vec3(1, 0, 0));
        assertArrayEquals(new long[]{coarse, ahead, behind}, keys);
    }

    @Test
    void authorizesDistantGeometryAndLimitsFineTilesToNearbyPlayers() {
        var start = new ChunkPos(0, 0);
        long fullDetail = WorldEngine.getWorldSectionId(0, 25, 0, 0);
        long halfway = WorldEngine.getWorldSectionId(1, 12, 0, 0);
        long distant = WorldEngine.getWorldSectionId(3, 3, 0, 0);
        long horizon = WorldEngine.getWorldSectionId(4, 4, 0, 0);
        assertFalse(LodSectionCursor.inRange(fullDetail, start, 128));
        assertFalse(LodSectionCursor.inRange(halfway, start, 128));
        assertTrue(LodSectionCursor.inRange(distant, start, 128));
        assertTrue(LodSectionCursor.inRange(horizon, start, 128));
        assertTrue(LodSectionCursor.inRange(fullDetail, new ChunkPos(40, 0), 128));
    }

    @Test
    void enumeratesEveryChunkInDistanceOrderAtNegativeCoordinates() {
        var center = new ChunkPos(-7, -8);
        var cursor = new NearChunkCursor(center, 3);
        assertEquals(center, cursor.peek().position());
        var positions = new HashSet<ChunkPos>();
        long previous = 0;
        while (cursor.peek() != null) {
            var next = cursor.peek();
            assertTrue(positions.add(next.position()));
            assertTrue(next.distanceSquared() >= previous && next.distanceSquared() <= 9);
            previous = next.distanceSquared();
            cursor.advance();
        }
        assertEquals(29, positions.size());
    }
}
