package me.cortex.voxy.server;

import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerSectionStorageTest {
    @Test
    void reopensCompletedColumnsRevisionsAndPalette(@TempDir Path temp) throws Exception {
        var path = temp.resolve("lod.sqlite");
        long key = WorldEngine.getWorldSectionId(4, -1, 0, 3);
        long column = 61;
        var data = new long[WorldSection.SECTION_VOLUME];
        data[9] = 0xf0_0000_0020_0000L;
        try (var store = new ServerSectionStorage(path)) {
            assertFalse(store.hasColumn(column));
            assertNull(store.read(key));
            store.putIdMapping(21, ByteBuffer.wrap(new byte[]{5, 8, 13}));
            var snapshot = new LodSectionSnapshot(key, (byte) 2,
                    SurfaceDataCoverage.legacy(4).withKnownInput(2, 0, 0), data);
            store.commitColumn(column, List.of(snapshot));
            assertEquals(1, store.revision(key));
            store.commitColumn(column, List.of(snapshot));
        }
        try (var store = new ServerSectionStorage(path)) {
            assertTrue(store.hasColumn(column));
            var revision = store.read(key);
            assertEquals(2, revision.number());
            assertArrayEquals(data, revision.section().voxels());
            assertEquals(1, revision.section().coverage().knownInputCount());
            assertArrayEquals(new byte[]{5, 8, 13}, store.getIdMappingsData().get(21));
            var positions = new ArrayList<Long>();
            store.iteratePositions(4, positions::add);
            assertEquals(List.of(key), positions);
        }
    }
}
