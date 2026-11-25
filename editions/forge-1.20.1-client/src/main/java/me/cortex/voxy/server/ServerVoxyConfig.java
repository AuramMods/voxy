package me.cortex.voxy.server;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class ServerVoxyConfig {
    public static final String FILE_NAME = "voxy-server.toml";
    public static final int MAX_RADIUS = 2048;
    public static final int MAX_CHUNK_JOBS = 64;
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue GENERATE;
    public static final ForgeConfigSpec.IntValue RADIUS;
    public static final ForgeConfigSpec.IntValue ONLINE_CHUNK_JOBS;
    public static final ForgeConfigSpec.IntValue IDLE_CHUNK_JOBS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPAWN_DIMENSIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPAWN_ANCHORS;
    private static final List<ForgeConfigSpec.ConfigValue<?>> OPTIONS;

    static {
        var builder = new ForgeConfigSpec.Builder();
        GENERATE = builder.comment("Generate missing Minecraft terrain. Saved chunks can always be converted to LODs.")
                .define("distantGeneration", true);
        RADIUS = builder.comment("Horizontal generation and streaming radius in Minecraft chunks.")
                .defineInRange("radiusChunks", 256, 1, MAX_RADIUS);
        ONLINE_CHUNK_JOBS = builder.comment("Maximum in-flight Voxy chunk jobs across all dimensions with players online.")
                .defineInRange("chunkJobs", 4, 1, MAX_CHUNK_JOBS);
        IDLE_CHUNK_JOBS = builder.comment("Maximum in-flight Voxy chunk jobs when no players are online.")
                .defineInRange("idleChunkJobs", 4, 1, MAX_CHUNK_JOBS);
        SPAWN_DIMENSIONS = builder.comment("Ordered spawn generation dimensions. Absent dimensions are skipped.")
                .defineListAllowEmpty("spawnDimensions",
                        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                        value -> value instanceof String id && isDimensionId(id));
        SPAWN_ANCHORS = builder.comment("Optional spawn overrides as dimension=x,z using block coordinates.")
                .defineListAllowEmpty("spawnAnchors", List.<String>of(),
                        value -> value instanceof String entry && isSpawnAnchor(entry));
        SPEC = builder.build();
        OPTIONS = List.of(GENERATE, RADIUS, ONLINE_CHUNK_JOBS, IDLE_CHUNK_JOBS, SPAWN_DIMENSIONS, SPAWN_ANCHORS);
    }

    static int chunkJobs(boolean playersOnline) {
        return (playersOnline ? ONLINE_CHUNK_JOBS : IDLE_CHUNK_JOBS).get();
    }

    public static boolean isDimensionId(String value) {
        return value.contains(":") && ResourceLocation.tryParse(value) != null;
    }

    public static boolean isSpawnAnchor(String value) {
        return parseAnchor(value) != null;
    }

    public static CommentedConfig snapshot() {
        var config = TomlFormat.newConfig();
        for (var value : OPTIONS) config.set(value.getPath(), value.get());
        return config;
    }

    static void apply(CommentedConfig config) {
        for (var value : OPTIONS) apply(config, value);
        SPEC.save();
    }

    private static <T> void apply(CommentedConfig config, ForgeConfigSpec.ConfigValue<T> value) {
        value.set(config.get(value.getPath()));
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
