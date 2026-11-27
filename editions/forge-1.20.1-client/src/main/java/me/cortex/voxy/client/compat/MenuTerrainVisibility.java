package me.cortex.voxy.client.compat;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;

import java.util.function.BooleanSupplier;

@Mod.EventBusSubscriber(modid = "voxy", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MenuTerrainVisibility {
    private static BooleanSupplier covered = () -> false;

    private MenuTerrainVisibility() {}

    @SubscribeEvent
    public static void connect(InterModProcessEvent event) {
        event.getIMCStream("auram_menu_coverage_v1"::equals)
                .filter(message -> message.senderModId().equals("auram"))
                .forEach(message -> {
                    if (message.messageSupplier().get() instanceof BooleanSupplier supplier) covered = supplier;
                });
    }

    public static boolean isHidden() {
        return covered.getAsBoolean();
    }
}
