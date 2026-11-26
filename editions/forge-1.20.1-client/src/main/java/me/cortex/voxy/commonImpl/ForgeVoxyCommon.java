package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.network.LodChannel;
import me.cortex.voxy.server.ServerVoxyConfig;
import me.cortex.voxy.server.WorldEditLodHooks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;


@Mod("voxy")
public class ForgeVoxyCommon extends VoxyCommon {
    public ForgeVoxyCommon() {
        LodChannel.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerVoxyConfig.SPEC, ServerVoxyConfig.FILE_NAME);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        com.mojang.logging.LogUtils.getLogger().info("Initializing Voxy Common");
    }

    private void setup(FMLCommonSetupEvent event) {
        if (ModList.get().isLoaded("worldedit")) event.enqueueWork(WorldEditLodHooks::register);
    }
}
