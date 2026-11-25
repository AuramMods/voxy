package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.server.ServerVoxyConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;


@Mod("voxy")
public class ForgeVoxyCommon extends VoxyCommon {
    public ForgeVoxyCommon() {
        LodChannel.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerVoxyConfig.SPEC, "voxy-server.toml");
        com.mojang.logging.LogUtils.getLogger().info("Initializing Voxy Common");
    }
}
