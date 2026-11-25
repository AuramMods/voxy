package me.cortex.voxy.server;

import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;
import java.util.PriorityQueue;

/** Merges sorted rows of a circle, keeping only one candidate per row. */
final class NearChunkCursor {
    record Candidate(ChunkPos position, long distanceSquared) {}

    private static final class Row {
        final int z;
        final int maxX;
        int x;

        Row(int z, int maxX) {
            this.z = z;
            this.maxX = maxX;
        }

        long distanceSquared() {
            return (long) this.x * this.x + (long) this.z * this.z;
        }

        boolean advance() {
            this.x = this.x < 0 ? -this.x : -this.x - 1;
            return Math.abs(this.x) <= this.maxX;
        }
    }

    private final ChunkPos center;
    private final PriorityQueue<Row> rows = new PriorityQueue<>(Comparator
            .comparingLong(Row::distanceSquared).thenComparingInt(row -> row.x).thenComparingInt(row -> row.z));

    NearChunkCursor(ChunkPos center, int radius) {
        this.center = center;
        long squared = (long) radius * radius;
        for (int z = -radius; z <= radius; z++) {
            this.rows.add(new Row(z, (int) Math.sqrt(squared - (long) z * z)));
        }
    }

    Candidate peek() {
        Row row = this.rows.peek();
        return row == null ? null
                : new Candidate(new ChunkPos(this.center.x + row.x, this.center.z + row.z), row.distanceSquared());
    }

    void advance() {
        Row row = this.rows.remove();
        if (row.advance()) this.rows.add(row);
    }
}
