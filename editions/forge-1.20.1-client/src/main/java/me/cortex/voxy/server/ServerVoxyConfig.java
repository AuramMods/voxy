package me.cortex.voxy.server;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class ServerVoxyConfig {
    public static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.BooleanValue GENERATE;
    static final ForgeConfigSpec.IntValue RADIUS;
    static final ForgeConfigSpec.IntValue CHUNK_JOBS;
    static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPAWN_DIMENSIONS;
    static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPAWN_ANCHORS;

    static {
        var builder = new ForgeConfigSpec.Builder();
        GENERATE = builder.comment("Generate missing Minecraft terrain. Saved chunks can always be converted to LODs.")
                .define("distantGeneration", true);
        RADIUS = builder.comment("Horizontal generation and streaming radius in Minecraft chunks.")
                .defineInRange("radiusChunks", 128, 1, 2048);
        CHUNK_JOBS = builder.comment("Maximum number of in-flight Voxy chunk jobs across all dimensions.")
                .defineInRange("chunkJobs", 4, 1, 64);
        SPAWN_DIMENSIONS = builder.comment("Ordered spawn generation dimensions. Absent dimensions are skipped.")
                .defineListAllowEmpty("spawnDimensions",
                        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                        value -> value instanceof String id && id.contains(":") && ResourceLocation.tryParse(id) != null);
        SPAWN_ANCHORS = builder.comment("Optional spawn overrides as dimension=x,z using block coordinates.")
                .defineListAllowEmpty("spawnAnchors", List.<String>of(),
                        value -> value instanceof String entry && parseAnchor(entry) != null);
        SPEC = builder.build();
    }

    record SpawnAnchor(ResourceLocation dimension, int x, int z) {}

    static SpawnAnchor parseAnchor(String text) {
        int separator = text.indexOf('=');
        int comma = text.indexOf(',', separator + 1);
        if (separator < 1 || comma <= separator + 1) return null;
        var dimension = ResourceLocation.tryParse(text.substring(0, separator).trim());
        if (dimension == null) return null;
        try {
            int x = Integer.parseInt(text.substring(separator + 1, comma).trim());
            int z = Integer.parseInt(text.substring(comma + 1).trim());
            return new SpawnAnchor(dimension, x, z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ServerVoxyConfig() {}
}
