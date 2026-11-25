package me.cortex.voxy.common.network;

import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LodSectionCodecTest {
    private record TestPalette(int block, int biome) implements LodSectionCodec.Palette {
        public String blockName(int id) {
            assertEquals(this.block, id);
            return "minecraft:oak_stairs[facing=west,waterlogged=true]";
        }

        public String biomeName(int id) {
            assertEquals(this.biome, id);
            return "minecraft:windswept_hills";
        }

        public int blockId(String name) {
            assertEquals(this.blockName(this.block), name);
            return this.block;
        }

        public int biomeId(String name) {
            assertEquals(this.biomeName(this.biome), name);
            return this.biome;
        }
    }

    @Test
    void remapsPaletteAndPreservesPartialCoverageAndSurfaceCarriers() throws IOException {
        var coverage = SurfaceDataCoverage.legacy(1).withKnownInput(2, 0, 0);
        var cells = new long[WorldSection.SECTION_VOLUME];
        cells[2] = Mapper.composeMappingId((byte) 0xcf, 3, 5);
        cells[3] = Mapper.makeSurfaceCarrier(cells[2]);
        cells[4] = Mapper.airWithLight(15);
        var section = new LodSectionSnapshot(WorldEngine.getWorldSectionId(1, -1, -2, 3), (byte) 0x84, coverage, cells);
        byte[] wire = LodSectionCodec.encode(section, new TestPalette(3, 5));

        var read = LodSectionCodec.decode(section.key(), wire, new TestPalette(19, 7));
        assertEquals(section.children(), read.children());
        assertEquals(coverage.word(0), read.coverage().word(0));
        assertEquals(0, read.voxels()[0]);
        assertEquals(Mapper.composeMappingId((byte) 0xcf, 19, 7), read.voxels()[2]);
        assertEquals(Mapper.makeSurfaceCarrier(read.voxels()[2]), read.voxels()[3]);
        assertEquals(Mapper.airWithLight(15), read.voxels()[4]);
        assertTrue(wire.length < 1024);
    }

    @Test
    void preservesKnownEmptyAndRejectsIncompleteOrTrailingStreams() throws IOException {
        var key = WorldEngine.getWorldSectionId(0, 0, 0, 0);
        var section = new LodSectionSnapshot(key, (byte) 0, SurfaceDataCoverage.raw(0),
                new long[WorldSection.SECTION_VOLUME]);
        var palette = new TestPalette(1, 1);
        byte[] wire = LodSectionCodec.encode(section, palette);
        assertEquals(1, LodSectionCodec.decode(key, wire, palette).coverage().mode());
        assertThrows(IOException.class, () -> LodSectionCodec.decode(key, Arrays.copyOf(wire, wire.length - 1), palette));
        assertThrows(IOException.class, () -> LodSectionCodec.decode(key, Arrays.copyOf(wire, wire.length + 1), palette));
        assertThrows(IOException.class, () -> LodSectionCodec.decode(key, new byte[LodSectionCodec.MAX_COMPRESSED_BYTES + 1], palette));
    }
}
