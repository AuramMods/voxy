package me.cortex.voxy.client.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.google.common.collect.ImmutableList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.server.ServerLodService;
import me.cortex.voxy.server.ServerVoxyConfig;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;

final class VoxyServerConfigPage implements OptionStorage<CommentedConfig> {
    private static final String KEY = "voxy.config.server";
    private final Path template = FMLPaths.GAMEDIR.get().resolve(FMLConfig.defaultConfigPath())
            .resolve(ServerVoxyConfig.FILE_NAME);
    private final IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
    private final CommentedConfig settings = this.load();

    static OptionPage create() {
        var storage = new VoxyServerConfigPage();
        var generation = OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, storage)
                        .setName(Component.translatable(KEY + ".generation"))
                        .setTooltip(Component.translatable(KEY + ".generation.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((config, value) -> config.set(ServerVoxyConfig.GENERATE.getPath(), value),
                                config -> config.get(ServerVoxyConfig.GENERATE.getPath()))
                        .build())
                .add(storage.slider("radius", ServerVoxyConfig.RADIUS, ServerVoxyConfig.MAX_RADIUS))
                .add(storage.slider("onlineJobs", ServerVoxyConfig.ONLINE_CHUNK_JOBS, ServerVoxyConfig.MAX_CHUNK_JOBS))
                .add(storage.slider("idleJobs", ServerVoxyConfig.IDLE_CHUNK_JOBS, ServerVoxyConfig.MAX_CHUNK_JOBS))
                .build();
        var spawn = OptionGroup.createBuilder()
                .add(storage.list("dimensions", ServerVoxyConfig.SPAWN_DIMENSIONS, ServerVoxyConfig::isDimensionId))
                .add(storage.list("anchors", ServerVoxyConfig.SPAWN_ANCHORS, ServerVoxyConfig::isSpawnAnchor))
                .build();
        IntFunction<Component> bandwidth = value -> Component.literal(value + " KiB/s");
        var streaming = OptionGroup.createBuilder()
                .add(storage.slider("playerStreamRate", ServerVoxyConfig.PLAYER_STREAM_RATE,
                        ServerVoxyConfig.MAX_STREAM_KIB_PER_SECOND, bandwidth))
                .add(storage.slider("serverStreamRate", ServerVoxyConfig.SERVER_STREAM_RATE,
                        ServerVoxyConfig.MAX_STREAM_KIB_PER_SECOND, bandwidth))
                .build();
        return new OptionPage(Component.translatable(KEY), ImmutableList.of(generation, spawn, streaming));
    }

    private Option<Integer> slider(String key, ConfigValue<Integer> setting, int max) {
        return this.slider(key, setting, max, value -> Component.literal(Integer.toString(value)));
    }

    private Option<Integer> slider(String key, ConfigValue<Integer> setting, int max, IntFunction<Component> formatter) {
        return OptionImpl.createBuilder(int.class, this)
                .setName(Component.translatable(KEY + "." + key))
                .setTooltip(Component.translatable(KEY + "." + key + ".tooltip"))
                .setControl(option -> new SliderControl(option, 1, max, 1,
                        formatter::apply))
                .setBinding((config, value) -> config.set(setting.getPath(), value),
                        config -> config.get(setting.getPath()))
                .build();
    }

    private Option<String> list(String key, ConfigValue<List<? extends String>> setting, Predicate<String> validator) {
        String translation = KEY + "." + key;
        return OptionImpl.createBuilder(String.class, this)
                .setName(Component.translatable(translation))
                .setTooltip(Component.translatable(translation + ".tooltip"))
                .setControl(option -> new ServerOptionListControl(option, validator))
                .setBinding((config, value) -> config.set(setting.getPath(), ServerOptionListControl.entries(value)),
                        config -> String.join("\n", config.<List<String>>get(setting.getPath())))
                .build();
    }

    private CommentedConfig load() {
        if (this.server != null && ServerVoxyConfig.SPEC.isLoaded()) return ServerVoxyConfig.snapshot();
        try (var reader = Files.newBufferedReader(this.template)) {
            var config = TomlFormat.instance().createParser().parse(reader);
            ServerVoxyConfig.SPEC.correct(config);
            return config;
        } catch (NoSuchFileException ignored) {
            // Forge also starts with the spec defaults when no template exists.
        } catch (IOException | ParsingException failure) {
            Logger.warn("Could not read the Voxy server template", failure);
        }
        var defaults = TomlFormat.newConfig();
        ServerVoxyConfig.SPEC.correct(defaults);
        return defaults;
    }

    @Override
    public CommentedConfig getData() {
        return this.settings;
    }

    @Override
    public void save() {
        var snapshot = CommentedConfig.copy(this.settings);
        ServerVoxyConfig.SPEC.correct(snapshot);
        try {
            Files.createDirectories(this.template.getParent());
            var temporary = Files.createTempFile(this.template.getParent(), "voxy-server-", ".tmp");
            try {
                try (var writer = Files.newBufferedWriter(temporary)) {
                    TomlFormat.instance().createWriter().write(snapshot, writer);
                }
                Files.move(temporary, this.template, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            Logger.error("Could not save the Voxy server template", failure);
            return;
        }
        if (this.server != null) ServerLodService.applyLocalConfig(this.server, snapshot);
    }
}
