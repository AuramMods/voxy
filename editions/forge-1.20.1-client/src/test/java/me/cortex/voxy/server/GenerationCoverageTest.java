package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GenerationCoverageTest {
    @Test
    void countsPersistedColumnsOnceAcrossOverlappingAnchorsAndClipsToBorder(@TempDir Path temp) throws Exception {
        var path = temp.resolve("lod.sqlite");
        var border = new WorldBorder();
        border.setCenter(8.0, -9.0);
        border.setSize(64.0);
        var bounds = GenerationCoverage.Bounds.of(border);
        var first = new GenerationCoverage.Area(new ChunkPos(0, -1), 2);
        var second = new GenerationCoverage.Area(new ChunkPos(1, -1), 2);

        try (var storage = new LodSectionStorage(path)) {
            storage.commitColumn(ChunkPos.asLong(-1, -1), List.of());
            storage.commitColumn(ChunkPos.asLong(0, -1), List.of());
            storage.commitColumn(ChunkPos.asLong(2, -1), List.of());
            storage.commitColumn(ChunkPos.asLong(3, -1), List.of()); // Inside the circle but beyond the border.
            storage.commitColumn(ChunkPos.asLong(-2, -2), List.of()); // Inside the border but outside both circles.
            storage.commitColumn(ChunkPos.asLong(0, 0), List.of());
        }

        assertFalse(border.isWithinBounds(new ChunkPos(3, -1)));
        assertEquals(new GenerationCoverage.Count(4, 17, false),
                GenerationCoverage.read(path, bounds, List.of(first, first, second)));
    }

    @Test
    void extrapolatesSampledRowsForLargeAreas(@TempDir Path temp) throws Exception {
        var path = temp.resolve("lod.sqlite");
        var bounds = new GenerationCoverage.Bounds(-600, 600, -600, 600);
        var target = new GenerationCoverage.Area(new ChunkPos(0, 0), 600);
        try (var storage = new LodSectionStorage(path)) {
            storage.commitColumn(ChunkPos.asLong(-1, -1), List.of());
            storage.commitColumn(ChunkPos.asLong(0, 1), List.of());
        }

        assertEquals(new GenerationCoverage.Count(4, 1_130_913, true),
                GenerationCoverage.read(path, bounds, List.of(target)));
    }

    @Test
    void missingDimensionStorageDoesNotCreateAFileOrFillGapsBetweenTargets(@TempDir Path temp) throws Exception {
        var path = temp.resolve("lod.sqlite");
        var bounds = new GenerationCoverage.Bounds(-100, 100, -1_000_000, 1_000_000);
        var targets = List.of(new GenerationCoverage.Area(new ChunkPos(0, -999_999), 1),
                new GenerationCoverage.Area(new ChunkPos(0, 999_999), 1));

        assertEquals(new GenerationCoverage.Count(0, 10, false), GenerationCoverage.read(path, bounds, targets));
        assertFalse(Files.exists(path));
    }

    @Test
    void formatsCombinedCoverageAndIdentifiesSampledCounts() {
        var first = new GenerationCoverage.Count(500, 1_000, false);
        var second = new GenerationCoverage.Count(750, 1_500, true);

        assertEquals("~50.00% (1,250/2,500 chunks covered, 1,250 left)", first.plus(second).format());
        assertEquals("99.99% (99,999/100,000 chunks covered, 1 left)",
                new GenerationCoverage.Count(99_999, 100_000, false).format());
    }
}
