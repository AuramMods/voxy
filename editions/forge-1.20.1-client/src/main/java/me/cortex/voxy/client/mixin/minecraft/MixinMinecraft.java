package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.compat.GlaAtlasUploads;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {
    @Inject(method = "tick", at = @At("RETURN"))
    private void voxy$updateMeshJobLimit(CallbackInfo ci) {
        var renderer = IGetVoxyRenderSystem.getNullable();
        if (renderer != null) renderer.updateMeshJobLimit();
    }

    @Inject(method = "close", at = @At("HEAD"), remap = false)
    private void voxy$freeAtlasUploadResources(CallbackInfo ci) {
        GlaAtlasUploads.close();
    }

    // 暂停菜单直接调用带 Screen 的重载，不能只挂无参入口。
    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void voxy$injectClearLevel(CallbackInfo ci) {
        voxy$closeSession();
    }

    private static void voxy$closeSession() {
        if (ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }
}
