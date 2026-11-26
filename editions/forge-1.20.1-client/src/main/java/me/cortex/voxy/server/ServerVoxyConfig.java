package me.cortex.voxy.server;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ConfigTracker;

import java.nio.file.Path;
import java.util.List;

public final class ServerVoxyConfig {
    public static final String FILE_NAME = "voxy-server.toml";
    public static final int MAX_RADIUS = 2048;
    public static final int MAX_CHUNK_JOBS = 64;
    public static final int MAX_STREAM_KIB_PER_SECOND = 16_384;
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue GENERATE;
    public static final ForgeConfigSpec.IntValue RADIUS;
    public static final ForgeConfigSpec.IntValue ONLINE_CHUNK_JOBS;
    public static final ForgeConfigSpec.IntValue IDLE_CHUNK_JOBS;
    public static final ForgeConfigSpec.IntValue PLAYER_STREAM_RATE;
    public static final ForgeConfigSpec.IntValue SERVER_STREAM_RATE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPAWN_DIMENSIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPAWN_ANCHORS;
    private static final List<ForgeConfigSpec.ConfigValue<?>> OPTIONS;
    private final CommentedConfig data;

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
        PLAYER_STREAM_RATE = builder.comment("Voxy terrain bandwidth per remote player in KiB/s at 20 TPS.")
                .defineInRange("playerStreamKiBPerSecond", 512, 1, MAX_STREAM_KIB_PER_SECOND);
        SERVER_STREAM_RATE = builder.comment("Total Voxy terrain bandwidth for remote players in KiB/s at 20 TPS.")
                .defineInRange("serverStreamKiBPerSecond", 2048, 1, MAX_STREAM_KIB_PER_SECOND);
        SPAWN_DIMENSIONS = builder.comment("Ordered spawn generation dimensions. Absent dimensions are skipped.")
                .defineListAllowEmpty("spawnDimensions",
                        List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"),
                        value -> value instanceof String id && isDimensionId(id));
        SPAWN_ANCHORS = builder.comment("Optional spawn overrides as dimension=x,z using block coordinates.")
                .defineListAllowEmpty("spawnAnchors", List.<String>of(),
                        value -> value instanceof String entry && isSpawnAnchor(entry));
        SPEC = builder.build();
        OPTIONS = List.of(GENERATE, RADIUS, ONLINE_CHUNK_JOBS, IDLE_CHUNK_JOBS, PLAYER_STREAM_RATE,
                SERVER_STREAM_RATE, SPAWN_DIMENSIONS, SPAWN_ANCHORS);
    }

    ServerVoxyConfig(CommentedConfig data) {
        this.data = data;
    }

    static ServerVoxyConfig open(Path worldRoot) {
        // The client can replace Forge's shared server spec with an in-memory copy after the world opens.
        var tracked = ConfigTracker.INSTANCE.fileMap().get(FILE_NAME).getConfigData();
        if (tracked instanceof CommentedFileConfig file) return new ServerVoxyConfig(file);

        var file = CommentedFileConfig.builder(worldRoot.resolve("serverconfig").resolve(FILE_NAME)).sync().build();
        file.load();
        SPEC.correct(file);
        return new ServerVoxyConfig(file);
    }

    <T> T get(ForgeConfigSpec.ConfigValue<T> option) {
        return this.data.getOrElse(option.getPath(), option::getDefault);
    }

    int chunkJobs(boolean playersOnline) {
        return this.get(playersOnline ? ONLINE_CHUNK_JOBS : IDLE_CHUNK_JOBS);
    }

    public static boolean isDimensionId(String value) {
        return value.contains(":") && ResourceLocation.tryParse(value) != null;
    }

    public static boolean isSpawnAnchor(String value) {
        return parseAnchor(value) != null;
    }

    CommentedConfig snapshot() {
        var config = TomlFormat.newConfig();
        for (var value : OPTIONS) config.set(value.getPath(), this.get(value));
        return config;
    }

    void apply(CommentedConfig config) {
        for (var value : OPTIONS) this.data.set(value.getPath(), config.get(value.getPath()));
        if (this.data instanceof CommentedFileConfig file) file.save();
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

}
