package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.IdMapper;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

class ServerChunkSnapshotTest {
    @Test
    void reusesFullyOverwrittenSectionsAndReturnsBuffersOnConversionFailure(@TempDir Path temp) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        final Holder<Biome> plains = Holder.Reference.createStandAlone(new HolderOwner<>() {}, Biomes.PLAINS);
        var biomeIds = new IdMapper<Holder<Biome>>();
        biomeIds.add(plains);
        var biomes = new PalettedContainer<>(biomeIds, plains, PalettedContainer.Strategy.SECTION_BIOMES);
        var blocks = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        blocks.set(3, 7, 9, Blocks.STONE.defaultBlockState());
        var air = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        var patterned = new ServerChunkSnapshot.Section(blocks, biomes, (x, y, z) -> (byte) (x ^ y << 4));
        var empty = new ServerChunkSnapshot.Section(air, biomes, (x, y, z) -> (byte) z);
        var buffers = new ArrayBlockingQueue<VoxelizedSection>(2);
        try (var storage = new LodSectionStorage(temp.resolve("voxy.sqlite"))) {
            var mapper = new Mapper(storage);
            var first = new ServerChunkSnapshot(new ChunkPos(4, -5), 7,
                    new ServerChunkSnapshot.Section[]{patterned, patterned});
            var second = new ServerChunkSnapshot(new ChunkPos(-12, 19), -4,
                    new ServerChunkSnapshot.Section[]{empty});
            VoxelizedSection previous;
            try (var prepared = first.voxelize(mapper, buffers)) {
                previous = prepared.sections()[0];
                assertEquals(1, previous.lvl0NonAirCount);
                assertNotSame(previous, prepared.sections()[1]);
                try (var fresh = second.voxelize(mapper, new ArrayBlockingQueue<>(1))) {
                    assertNotSame(previous, fresh.sections()[0]);
                }
            }
            try (var reused = second.voxelize(mapper, buffers);
                 var fresh = second.voxelize(mapper, new ArrayBlockingQueue<>(1))) {
                var section = reused.sections()[0];
                assertSame(previous, section);
                assertEquals(-12, section.x);
                assertEquals(-4, section.y);
                assertEquals(19, section.z);
                assertEquals(0, section.lvl0NonAirCount);
                assertArrayEquals(fresh.sections()[0].section, section.section);
            }
            ILightingSupplier failing = (x, y, z) -> { throw new IllegalStateException("fixture"); };
            var incomplete = new ServerChunkSnapshot(new ChunkPos(0, 0), 0,
                    new ServerChunkSnapshot.Section[]{patterned, new ServerChunkSnapshot.Section(air, biomes, failing)});
            assertThrows(IllegalStateException.class, () -> incomplete.voxelize(mapper, buffers));
            assertEquals(2, buffers.size());
        }
    }
}
