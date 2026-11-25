package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.config.section.LodSectionStorage.TileVersion;
import me.cortex.voxy.common.config.section.LodSectionStorage.WireTile;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerSectionStorageTest {
    @Test
    void readOnlyViewSeesCommitsAndSurvivesWriterClose(@TempDir Path temp) throws Exception {
        var path = temp.resolve("local.sqlite");
        long key = WorldEngine.getWorldSectionId(0, 7, 0, -8);
        var snapshot = new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.raw(0),
                new long[WorldSection.SECTION_VOLUME]);
        try (var writer = new LodSectionStorage(path);
             var reader = LodSectionStorage.openReadOnly(path)) {
            assertNull(reader.read(key));
            writer.commitColumn(1, List.of(snapshot));
            assertEquals(1, reader.revision(key));
            snapshot.voxels()[0] = 0x1000_0000;
            writer.commitColumn(1, List.of(snapshot));
            writer.close();
            assertEquals(2, reader.revision(key));
            assertEquals(0x1000_0000, reader.read(key).section().voxels()[0]);
            assertThrows(IllegalStateException.class, () -> reader.commitColumn(1, List.of(snapshot)));
        }
    }

    @Test
    void reopensCompletedColumnsRevisionsAndPalette(@TempDir Path temp) throws Exception {
        var path = temp.resolve("lod.sqlite");
        long key = WorldEngine.getWorldSectionId(4, -1, 0, 3);
        long column = 61;
        var data = new long[WorldSection.SECTION_VOLUME];
        data[9] = 0xf0_0000_0020_0000L;
        try (var store = new LodSectionStorage(path)) {
            assertFalse(store.hasColumn(column));
            assertNull(store.read(key));
            store.putIdMapping(21, ByteBuffer.wrap(new byte[]{5, 8, 13}));
            var snapshot = new LodSectionSnapshot(key, (byte) 2,
                    SurfaceDataCoverage.legacy(4).withKnownInput(2, 0, 0), data);
            store.commitColumn(column, List.of(snapshot));
            assertEquals(1, store.revision(key));
            store.commitColumn(column, List.of(snapshot));
            assertEquals(1, store.revision(key));
            data[10] = 0x1000_0000;
            store.commitColumn(column, List.of(snapshot));
        }
        try (var store = new LodSectionStorage(path)) {
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
    @Test
    void acknowledgesRemoteRevisionOnlyWithItsStoredVoxels(@TempDir Path temp) throws Exception {
        var path = temp.resolve("remote.sqlite");
        var key = WorldEngine.getWorldSectionId(0, 7, 0, -8);
        var snapshot = new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.raw(0),
                new long[WorldSection.SECTION_VOLUME]);
        var session = UUID.randomUUID();
        try (var store = new LodSectionStorage(path)) {
            assertTrue(store.commitRemote(snapshot, session, new TileVersion(13, 19), (byte) 0));
            snapshot.voxels()[0] = 1;
            assertFalse(store.commitRemote(snapshot, session, new TileVersion(12, 20), (byte) 1));
        }
        try (var store = new LodSectionStorage(path)) {
            assertEquals(13, store.remoteTile(key).version().revision());
            assertEquals(0, store.read(key).section().voxels()[0]);
            assertFalse(store.needsRemote(key, session, new TileVersion(15, 19)));
            assertEquals(15, store.remoteTile(key).version().revision());
            assertFalse(store.needsRemote(key, session, new TileVersion(14, 20)));
            var nextSession = UUID.randomUUID();
            assertFalse(store.commitRemote(snapshot, nextSession, new TileVersion(13, 19), (byte) 0));
            assertTrue(store.commitRemote(snapshot, nextSession, new TileVersion(7, 20), (byte) 0));
            assertEquals(1, store.read(key).section().voxels()[0]);
            assertFalse(store.commitRemote(snapshot, nextSession, new TileVersion(6, 21), (byte) 0));
            assertFalse(store.forgetRemote(key, nextSession));
            assertTrue(store.forgetRemote(key, UUID.randomUUID()));
            assertNull(store.read(key));
            assertNull(store.remoteTile(key));
        }
    }

    @Test
    void refusesToServeWireDataForAnOlderStorageRevision(@TempDir Path temp) throws Exception {
        var key = WorldEngine.getWorldSectionId(0, 7, 0, -8);
        var snapshot = new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.raw(0),
                new long[WorldSection.SECTION_VOLUME]);
        byte[] encoded = {1, 2, 3};
        var first = new WireTile(new TileVersion(1, 42), encoded);
        try (var store = new LodSectionStorage(temp.resolve("lod.sqlite"))) {
            store.commitColumn(0, List.of(snapshot));
            assertTrue(store.cacheWireTile(key, first));
            assertArrayEquals(encoded, store.wireTile(key).data());
            assertEquals(first.version(), store.wireVersion(key));
            snapshot.voxels()[0] = 0x1000_0000;
            store.commitColumn(0, List.of(snapshot));
            assertNull(store.wireTile(key));
            assertNull(store.wireVersion(key));
            assertFalse(store.cacheWireTile(key, first));
        }
    }
}
