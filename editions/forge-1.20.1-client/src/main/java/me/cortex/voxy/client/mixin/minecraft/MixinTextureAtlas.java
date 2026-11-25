package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClient;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public class MixinTextureAtlas {
    @Shadow @Final private ResourceLocation location;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void voxy$pauseBlockAtlasAnimations(CallbackInfo ci) {
        if (!VoxyClient.blockAtlasAnimationsEnabled() && TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
            ci.cancel();
        }
    }
}
