package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import static me.cortex.voxy.client.core.rendering.building.RenderDataFactory.UNKNOWN_AIR;
import static me.cortex.voxy.client.core.rendering.building.RenderDataFactory.opaqueFaceLight;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvisionalLightingTest {
    @Test
    void distinguishesUnloadedInputsFromKnownAirAndLegacyTiles() {
        var partial = SurfaceDataCoverage.legacy(1).withKnownInput(0, 0, 0);
        assertFalse(partial.isUncoveredVoxel(0));
        assertTrue(partial.isUncoveredVoxel(8));
        assertFalse(SurfaceDataCoverage.raw(1).isUncoveredVoxel(8));
        assertFalse(SurfaceDataCoverage.legacy(1).isUncoveredVoxel(8));
    }

    @Test
    void borrowsLocalSkylightOnlyForMissingNeighbors() {
        var voxels = new long[32 * 32 * 32];
        int blockIndex = 5 + (9 << 5) + (16 << 10);
        voxels[blockIndex] = Mapper.withLight(0, 1);
        voxels[blockIndex + 1024] = Mapper.airWithLight(0xB4);
        voxels[blockIndex + 2048] = Mapper.airWithLight(0x7F);

        assertEquals(13L << 55, opaqueFaceLight(UNKNOWN_AIR, voxels, blockIndex));
        assertEquals(0, opaqueFaceLight(0, voxels, blockIndex));
        assertEquals(0x36L << 55, opaqueFaceLight(0x36L << 55, voxels, blockIndex));
    }

    @Test
    void leavesDarkColumnsDarkAndStopsAtSectionTop() {
        var voxels = new long[32 * 32 * 32];
        int blockIndex = voxels.length - 1;
        voxels[blockIndex] = Mapper.airWithLight(0xF0);
        voxels[blockIndex - 1024] = Mapper.airWithLight(15);

        assertEquals(0, opaqueFaceLight(UNKNOWN_AIR, voxels, blockIndex));
    }
}
