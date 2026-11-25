package me.cortex.voxy.common.network;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

public final class RegistryLodPalette implements LodSectionCodec.Palette {
    private final Mapper mapper;
    private final HolderLookup<Block> blocks;
    private final HolderLookup<Biome> biomes;
    private final Mapper.StateEntry[] states;
    private final Mapper.BiomeEntry[] biomeEntries;

    public RegistryLodPalette(Mapper mapper, RegistryAccess registries) {
        this.mapper = mapper;
        this.blocks = registries.lookupOrThrow(Registries.BLOCK);
        this.biomes = registries.lookupOrThrow(Registries.BIOME);
        this.states = mapper.getStateEntries();
        this.biomeEntries = mapper.getBiomeEntries();
    }

    @Override
    public String blockName(int id) {
        return BlockStateParser.serialize(this.states[id].state);
    }

    @Override
    public String biomeName(int id) {
        return this.biomeEntries[id].biome;
    }

    @Override
    public int blockId(String name) {
        try {
            return this.mapper.getIdForBlockState(BlockStateParser.parseForBlock(this.blocks, name, false).blockState());
        } catch (CommandSyntaxException failure) {
            return -1;
        }
    }

    @Override
    public int biomeId(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) return -1;
        return this.biomes.get(ResourceKey.create(Registries.BIOME, id)).map(this.mapper::getIdForBiome).orElse(-1);
    }
}
