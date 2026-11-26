package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.NativeImage;
import me.cortex.voxy.client.compat.GlaAtlasUploads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(NativeImage.class)
public class MixinNativeImage {
    @WrapOperation(method = "_upload", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/GlStateManager;_texSubImage2D(IIIIIIIIJ)V", remap = false))
    private void voxy$copyAtlasAnimation(int target, int level, int x, int y, int width, int height,
                                        int format, int type, long pixels, Operation<Void> original,
                                        @Local(argsOnly = true, ordinal = 3) int skipX,
                                        @Local(argsOnly = true, ordinal = 4) int skipY,
                                        @Local(argsOnly = true, ordinal = 3) boolean closesImage) {
        if (!GlaAtlasUploads.upload(target, level, x, y, width, height, format, type, pixels,
                ((NativeImage) (Object) this).getWidth(), skipX, skipY, closesImage)) {
            original.call(target, level, x, y, width, height, format, type, pixels);
        }
    }
}
