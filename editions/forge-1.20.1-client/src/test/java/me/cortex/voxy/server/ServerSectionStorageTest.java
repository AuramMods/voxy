package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.config.section.LodSectionStorage.TileVersion;
import me.cortex.voxy.common.config.section.LodSectionStorage.WireTile;
import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerSectionStorageTest {
    @Test
    void coalescesLocalSectionRefreshesAndStopsQueuedWorkOnClose(@TempDir Path temp) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var path = temp.resolve("local.sqlite");
        long key = WorldEngine.getWorldSectionId(0, 7, 0, -8);
        var snapshot = new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.raw(0),
                new long[WorldSection.SECTION_VOLUME]);
        var subscription = new AtomicReference<LocalLodTerrain>();
        var tasks = new ArrayDeque<Runnable>();
        var observed = new ArrayList<Long>();
        var observedFlags = new ArrayList<Integer>();
        try (var writer = new LodSectionStorage(path);
             var local = new LocalLodTerrain(Level.OVERWORLD, LodSectionStorage.openReadOnly(path), null,
                     subscription, tasks::addLast)) {
            subscription.set(local);
            local.world().setDirtyCallback((section, flags, neighbors) -> {
                observed.add(section._unsafeGetRawDataArray()[0]);
                observedFlags.add(flags);
            });
            writer.commitColumn(1, List.of(snapshot));
            local.queueRefresh(new long[]{key});
            snapshot.voxels()[0] = 0x1000_0000;
            writer.commitColumn(1, List.of(snapshot));
            local.queueRefresh(new long[]{key, key});
            assertEquals(1, tasks.size());
            tasks.removeFirst().run();
            assertEquals(List.of(0x1000_0000L), observed);
            assertEquals(WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_DONT_SAVE
                    | WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT, observedFlags.get(0));
            assertTrue(tasks.isEmpty());

            snapshot.voxels()[0] = 0x2000_0000;
            writer.commitColumn(1, List.of(snapshot));
            local.queueRefresh(new long[]{key});
            tasks.removeFirst().run();
            assertEquals(List.of(0x1000_0000L, 0x2000_0000L), observed);
            assertEquals(WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_DONT_SAVE, observedFlags.get(1));

            local.queueRefresh(new long[]{key});
            local.stopUpdates();
            tasks.removeFirst().run();
            local.queueRefresh(new long[]{key});
            assertTrue(tasks.isEmpty());
            assertNull(subscription.get());
            assertEquals(2, observed.size());
        }
    }

    @Test
    void truncatedStorageReadDoesNotPublishOrModifyASection(@TempDir Path temp) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var path = temp.resolve("local.sqlite");
        long warmKey = WorldEngine.getWorldSectionId(0, 7, 0, -8);
        long coldKey = WorldEngine.getWorldSectionId(0, 8, 0, -8);
        long[] voxels = new long[WorldSection.SECTION_VOLUME];
        voxels[0] = 0x1000_0000;
        var warm = new LodSectionSnapshot(warmKey, (byte) 1, SurfaceDataCoverage.raw(0), voxels);
        var cold = new LodSectionSnapshot(coldKey, (byte) 2, SurfaceDataCoverage.raw(0), voxels);
        try (var writer = new LodSectionStorage(path);
             var local = new LocalLodTerrain(Level.OVERWORLD, LodSectionStorage.openReadOnly(path), null,
                     new AtomicReference<>(), Runnable::run)) {
            writer.commitColumn(1, List.of(warm, cold));
            var section = local.world().acquire(warmKey);
            try {
                var truncated = new ByteArrayOutputStream();
                try (var out = new DataOutputStream(new DeflaterOutputStream(truncated))) {
                    out.writeByte(4);
                    out.writeByte(1);
                    out.writeLong(0x2000_0000);
                }
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                     var update = connection.prepareStatement("UPDATE section_v1 SET data=? WHERE id IN (?,?)")) {
                    update.setBytes(1, truncated.toByteArray());
                    update.setLong(2, warmKey);
                    update.setLong(3, coldKey);
                    assertEquals(2, update.executeUpdate());
                }

                assertThrows(IllegalStateException.class, () -> local.world().refreshFromStorage(warmKey));
                assertEquals(0x1000_0000, section._unsafeGetRawDataArray()[0]);
                assertEquals(1, section.getNonEmptyChildren());
                assertThrows(IllegalStateException.class, () -> local.world().acquire(coldKey));
                assertEquals(1, local.world().getActiveSectionCount());

                writer.commitColumn(1, List.of(cold));
                var recovered = local.world().acquire(coldKey);
                try {
                    assertEquals(0x1000_0000, recovered._unsafeGetRawDataArray()[0]);
                    assertEquals(2, recovered.getNonEmptyChildren());
                } finally {
                    recovered.release();
                }
            } finally {
                section.release();
            }
        }
    }

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
