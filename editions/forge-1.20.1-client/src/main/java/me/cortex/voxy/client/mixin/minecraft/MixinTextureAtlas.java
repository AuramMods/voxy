package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.cortex.voxy.client.compat.GlaAtlasUploads;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TextureAtlas.class)
public abstract class MixinTextureAtlas {
    @Shadow public abstract int getWidth();
    @Shadow public abstract int getHeight();
    @Shadow public abstract ResourceLocation location();

    @WrapMethod(method = "cycleAnimationFrames")
    private void voxy$uploadLargeAtlasAnimations(Operation<Void> original) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(this.location())
                && Math.max(this.getWidth(), this.getHeight()) >= 16384 && GlaAtlasUploads.isGla()) {
            GlaAtlasUploads.animate(() -> original.call());
        } else {
            original.call();
        }
    }
}
