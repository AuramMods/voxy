package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShadowRenderer.class, remap = false)
public class MixinShadowRenderer {
    @Shadow @Final private ShadowRenderTargets targets;
    @Shadow @Final private float halfPlaneLength;

    @Unique
    private static VoxyRenderSystem voxy$renderer() {
        return ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
    }

    @Inject(method = "renderShadows", at = @At("HEAD"))
    private void voxy$beginShadowPass(LevelRendererAccessor levelRenderer, Camera camera, CallbackInfo ci) {
        var renderer = voxy$renderer();
        if (renderer != null && renderer.getShadowTerrainCoverage() != null) {
            renderer.getShadowTerrainCoverage().beginSolidPass();
        }
    }

    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/shadows/ShadowRenderer;copyPreTranslucentDepth(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;)V"))
    private void voxy$drawShadowCasters(LevelRendererAccessor levelRenderer, Camera camera, CallbackInfo ci) {
        var renderer = voxy$renderer();
        if (renderer == null) return;
        var position = camera.getPosition();
        renderer.renderShadowCasters(ShadowRenderer.PROJECTION, ShadowRenderer.MODELVIEW, position.x, position.y,
                position.z, this.halfPlaneLength, this.targets.getDepthTexture().getTextureId(), this.targets.getResolution());
    }
}
