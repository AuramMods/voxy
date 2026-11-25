package me.cortex.voxy.server;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class NearChunkCursorTest {
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
