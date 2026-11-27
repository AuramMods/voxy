package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.config.section.LodSectionStorage.ColumnRange;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/** Counts persisted LOD coverage across the union of a dimension's generation circles. */
final class GenerationCoverage {
    private static final long EXACT_SCAN_COLUMNS = 1_048_576;

    record Area(ChunkPos center, int radius) {}
    record Target(Bounds bounds, List<Area> areas) {}

    record Bounds(int minX, int maxX, int minZ, int maxZ) {
        static Bounds of(WorldBorder border) {
            // Match WorldBorder.isWithinBounds(ChunkPos), which uses the first and last block coordinates.
            return new Bounds(firstChunk(border.getMinX()), lastChunk(border.getMaxX()),
                    firstChunk(border.getMinZ()), lastChunk(border.getMaxZ()));
        }

        private static int firstChunk(double minBlock) {
            return (int) Math.floor((minBlock - 15) / 16) + 1;
        }

        private static int lastChunk(double maxBlock) {
            return (int) Math.ceil(maxBlock / 16) - 1;
        }
    }

    record Count(long covered, long total, boolean sampled) {
        Count plus(Count other) {
            return new Count(this.covered + other.covered, this.total + other.total, this.sampled || other.sampled);
        }

        String format() {
            double percent = this.covered == this.total ? 100.0 : Math.min(99.99, this.covered * 100.0 / this.total);
            return String.format(Locale.ROOT, "%s%.2f%% (%,d/%,d chunks covered, %,d left)",
                    this.sampled ? "~" : "", percent, this.covered, this.total, this.total - this.covered);
        }
    }

    private record Span(int min, int max) {
        long length() {
            return (long) this.max - this.min + 1;
        }

        void addKeys(int z, List<ColumnRange> ranges) {
            // The low half of a packed key orders X as unsigned. A span crossing zero needs two ranges.
            if (this.min < 0 && this.max >= 0) {
                ranges.add(new ColumnRange(ChunkPos.asLong(this.min, z), ChunkPos.asLong(-1, z)));
                ranges.add(new ColumnRange(ChunkPos.asLong(0, z), ChunkPos.asLong(this.max, z)));
            } else {
                ranges.add(new ColumnRange(ChunkPos.asLong(this.min, z), ChunkPos.asLong(this.max, z)));
            }
        }
    }

    static Count read(Path storagePath, Bounds bounds, List<Area> areas) throws SQLException {
        var rows = new TreeMap<Integer, List<Span>>();
        for (var area : areas) {
            var center = area.center();
            long radiusSquared = (long) area.radius() * area.radius();
            int firstZ = Math.max(bounds.minZ(), center.z - area.radius());
            int lastZ = Math.min(bounds.maxZ(), center.z + area.radius());
            for (int z = firstZ; z <= lastZ; z++) {
                long dz = (long) z - center.z;
                int halfWidth = (int) Math.sqrt(radiusSquared - dz * dz);
                int min = Math.max(bounds.minX(), center.x - halfWidth);
                int max = Math.min(bounds.maxX(), center.x + halfWidth);
                if (min <= max) rows.computeIfAbsent(z, ignored -> new ArrayList<>()).add(new Span(min, max));
            }
        }

        long total = 0;
        for (var spans : rows.values()) {
            merge(spans);
            for (var span : spans) total += span.length();
        }
        if (total == 0 || Files.notExists(storagePath)) return new Count(0, total, false);

        // Survey every row for ordinary radii. At large radii, spread indexed reads across the footprint.
        long stride = Math.min(rows.size(), (total - 1) / EXACT_SCAN_COLUMNS + 1);
        long sampledColumns = 0;
        int row = 0;
        var ranges = new ArrayList<ColumnRange>();
        for (var entry : rows.entrySet()) {
            if (row++ % stride != stride / 2) continue;
            for (var span : entry.getValue()) {
                span.addKeys(entry.getKey(), ranges);
                sampledColumns += span.length();
            }
        }
        try (var storage = LodSectionStorage.openReadOnly(storagePath)) {
            long covered = storage.countCompletedColumns(ranges);
            long estimate = stride == 1 ? covered : Math.round(covered * (double) total / sampledColumns);
            return new Count(estimate, total, stride != 1);
        }
    }

    private static void merge(List<Span> spans) {
        if (spans.size() == 1) return;
        spans.sort(Comparator.comparingInt(Span::min));
        int merged = 0;
        for (int i = 1; i < spans.size(); i++) {
            var previous = spans.get(merged);
            var next = spans.get(i);
            if (next.min() <= previous.max() + 1) {
                if (next.max() > previous.max()) spans.set(merged, new Span(previous.min(), next.max()));
            } else {
                spans.set(++merged, next);
            }
        }
        spans.subList(merged + 1, spans.size()).clear();
    }
}
