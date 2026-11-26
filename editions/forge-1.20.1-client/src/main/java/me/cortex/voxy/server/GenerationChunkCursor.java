package me.cortex.voxy.server;

import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** Keeps a short distance-ordered window so turning can reprioritize pending columns without restarting the scan. */
final class GenerationChunkCursor {
    private static final int LOOKAHEAD = 24;

    record View(float x, float z, int distanceOffset) {
        static final View OMNIDIRECTIONAL = new View(0, 0, 0);
        static final View ONLINE_SPAWN = new View(0, 0, 16);

        static View fromYaw(float degrees) {
            float yaw = degrees * Mth.DEG_TO_RAD;
            return new View(-Mth.sin(yaw), Mth.cos(yaw), 0);
        }

        double priority(ChunkPos center, ChunkPos position, long distanceSquared) {
            double distance = Math.sqrt(distanceSquared);
            double range = distance + this.distanceOffset;
            int band = (int) (range / 2);
            double forward = distance == 0 ? 0 : ((position.x - center.x) * (double) this.x
                    + (position.z - center.z) * (double) this.z) / distance;
            // The look bonus can reorder a two-chunk band. It cannot overtake a nearer band.
            return band * 4 + (range - band * 2) - 0.75 * forward;
        }
    }

    private final ChunkPos center;
    private final NearChunkCursor source;
    private final List<NearChunkCursor.Candidate> pending = new ArrayList<>(LOOKAHEAD);

    GenerationChunkCursor(ChunkPos center, int radius) {
        this.center = center;
        this.source = new NearChunkCursor(center, radius);
    }

    NearChunkCursor.Candidate peek(View view) {
        while (this.pending.size() < LOOKAHEAD) {
            var next = this.source.peek();
            if (next == null) break;
            this.pending.add(next);
            this.source.advance();
        }

        NearChunkCursor.Candidate best = null;
        double priority = Double.POSITIVE_INFINITY;
        for (var candidate : this.pending) {
            double score = view.priority(this.center, candidate.position(), candidate.distanceSquared());
            if (score < priority) {
                best = candidate;
                priority = score;
            }
        }
        return best;
    }

    boolean isExhausted() {
        return this.pending.isEmpty() && this.source.peek() == null;
    }

    void advance(ChunkPos position) {
        this.pending.removeIf(candidate -> candidate.position().equals(position));
    }
}
