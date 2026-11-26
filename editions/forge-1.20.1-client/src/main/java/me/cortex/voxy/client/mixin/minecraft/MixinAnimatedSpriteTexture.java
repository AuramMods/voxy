package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.compat.GlaAtlasUploads;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$AnimatedTexture")
public abstract class MixinAnimatedSpriteTexture {
    @WrapMethod(method = "uploadFrame")
    private void voxy$identifyFrame(int x, int y, int frame, Operation<Void> original) {
        if (GlaAtlasUploads.isAnimating()) {
            GlaAtlasUploads.immutableFrame(this, frame, () -> original.call(x, y, frame));
        } else {
            original.call(x, y, frame);
        }
    }
}
