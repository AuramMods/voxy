package me.cortex.voxy.server;

import com.mojang.serialization.Codec;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import java.util.Optional;

/** Only owned palettes and lights cross from the chunk lifecycle into the Voxy storage worker. */
record ServerChunkSnapshot(ChunkPos position, int minSection, Section[] sections) {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState());

    record Section(PalettedContainer<BlockState> blocks, PalettedContainerRO<Holder<Biome>> biomes,
                   ILightingSupplier light) {}

    static ServerChunkSnapshot fromLive(ServerLevel level, LevelChunk chunk) {
        var position = chunk.getPos();
        var minSection = level.getMinSection();
        var source = chunk.getSections();
        var sections = new Section[source.length];
        boolean hasSky = level.dimensionType().hasSkyLight();
        int[] heights = hasSky ? new int[256] : null;
        if (heights != null) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) heights[x | z << 4] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
            }
        }
        var engine = level.getLightEngine();
        for (int index = 0; index < source.length; index++) {
            var original = source[index];
            int y = minSection + index;
            var location = SectionPos.of(position, y);
            var block = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(location);
            var sky = hasSky ? engine.getLayerListener(LightLayer.SKY).getDataLayerData(location) : null;
            sections[index] = new Section(original.getStates().copy(), copyBiomes(level, original.getBiomes()),
                    lightSampler(block == null ? null : block.copy(), sky == null ? null : sky.copy(), heights, y << 4));
        }
        return new ServerChunkSnapshot(position, minSection, sections);
    }

    private static PalettedContainerRO<Holder<Biome>> copyBiomes(ServerLevel level,
            PalettedContainerRO<Holder<Biome>> biomes) {
        if (biomes instanceof PalettedContainer<Holder<Biome>> palette) return palette.copy();
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var copy = new PalettedContainer<>(registry.asHolderIdMap(), registry.getHolderOrThrow(Biomes.PLAINS),
                PalettedContainer.Strategy.SECTION_BIOMES);
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) copy.set(x, y, z, biomes.get(x, y, z));
            }
        }
        return copy;
    }

    static boolean isFull(CompoundTag chunk) {
        return ChunkStatus.byName(chunk.getString("Status")) == ChunkStatus.FULL;
    }

    static Optional<ServerChunkSnapshot> fromSaved(ServerLevel level, ChunkPos position, CompoundTag chunk) {
        if (!isFull(chunk)) return Optional.empty();
        if (chunk.getInt("xPos") != position.x || chunk.getInt("zPos") != position.z) {
            throw new IllegalArgumentException("Incorrect saved chunk position at " + position);
        }

        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var plains = biomeRegistry.getHolderOrThrow(Biomes.PLAINS);
        var biomeIds = biomeRegistry.asHolderIdMap();
        var biomeCodec = PalettedContainer.codecRO(biomeIds, biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, plains);
        var air = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
        var defaultBiome = new PalettedContainer<>(biomeIds, plains, PalettedContainer.Strategy.SECTION_BIOMES);
        var tags = new CompoundTag[level.getSectionsCount()];
        int minSection = level.getMinSection();
        for (Tag tag : chunk.getList("sections", Tag.TAG_COMPOUND)) {
            var section = (CompoundTag) tag;
            int index = section.getInt("Y") - minSection;
            if (index >= 0 && index < tags.length) tags[index] = section;
        }

        int[] heights = level.dimensionType().hasSkyLight() ? decodeHeights(level, chunk) : null;
        var sections = new Section[tags.length];
        for (int index = 0; index < tags.length; index++) {
            var section = tags[index];
            var blocks = section != null && section.contains("block_states", Tag.TAG_COMPOUND)
                    ? STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow(false, e -> {})
                    : air;
            var biomes = section != null && section.contains("biomes", Tag.TAG_COMPOUND)
                    ? biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes")).getOrThrow(false, e -> {})
                    : defaultBiome;
            DataLayer blockLight = lightLayer(section, "BlockLight");
            DataLayer skyLight = lightLayer(section, "SkyLight");
            int baseY = (minSection + index) << 4;
            sections[index] = new Section(blocks, biomes, lightSampler(blockLight, skyLight, heights, baseY));
        }
        return Optional.of(new ServerChunkSnapshot(position, minSection, sections));
    }

    private static ILightingSupplier lightSampler(DataLayer blockLight, DataLayer skyLight, int[] heights, int baseY) {
        return (x, y, z) -> {
            int block = blockLight == null ? 0 : blockLight.get(x, y, z);
            int sky = skyLight != null ? skyLight.get(x, y, z)
                    : heights != null && baseY + y >= heights[x | z << 4] ? 15 : 0;
            return (byte) (sky | block << 4);
        };
    }

    private static DataLayer lightLayer(CompoundTag section, String tag) {
        if (section == null || !section.contains(tag, Tag.TAG_BYTE_ARRAY)) return null;
        return new DataLayer(section.getByteArray(tag));
    }

    private static int[] decodeHeights(ServerLevel level, CompoundTag chunk) {
        long[] packed = chunk.getCompound("Heightmaps").getLongArray("WORLD_SURFACE");
        int bits = Mth.ceillog2(level.getHeight() + 1);
        int perLong = 64 / bits;
        if (packed.length * perLong < 256) return null;
        long mask = (1L << bits) - 1;
        var heights = new int[256];
        for (int index = 0; index < heights.length; index++) {
            heights[index] = level.getMinBuildHeight() + (int) ((packed[index / perLong] >>> (index % perLong * bits)) & mask);
        }
        return heights;
    }

    void insertInto(WorldEngine world) {
        var scratch = VoxelizedSection.createEmpty();
        for (int i = 0; i < this.sections.length; i++) {
            var section = this.sections[i];
            scratch.setPosition(this.position.x, this.minSection + i, this.position.z);
            WorldConversionFactory.convert(scratch, world.getMapper(), section.blocks(), section.biomes(), section.light());
            WorldVoxilizedSectionMipper.mipSection(scratch, world.getMapper());
            WorldUpdater.insertUpdate(world, scratch);
        }
    }
}
