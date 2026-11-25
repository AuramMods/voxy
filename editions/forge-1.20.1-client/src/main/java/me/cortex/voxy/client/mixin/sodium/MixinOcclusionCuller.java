package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.NearTerrainCoverage;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = OcclusionCuller.class, remap = false)
public class MixinOcclusionCuller {
    @Unique private static NearTerrainCoverage voxy$visibilityPass;

    @WrapMethod(method = "findVisible")
    private void voxy$trackVisibility(OcclusionCuller.Visitor visitor, Viewport viewport, float distance,
                                      boolean useOcclusionCulling, int frame, Operation<Void> original) {
        var previous = voxy$visibilityPass;
        voxy$visibilityPass = null;
        if (!IrisUtil.irisShadowActive()) {
            var system = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
            if (system != null) voxy$visibilityPass = system.getTerrainTransitionCoverage();
        }
        try {
            if (voxy$visibilityPass != null) voxy$visibilityPass.beginVisibilityPass();
            original.call(visitor, viewport, distance, useOcclusionCulling, frame);
        } finally {
            voxy$visibilityPass = previous;
        }
    }

    @WrapOperation(method = "isSectionVisible", at = @At(value = "INVOKE", target =
            "Lme/jellysquid/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;isWithinRenderDistance(Lme/jellysquid/mods/sodium/client/render/viewport/CameraTransform;Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;F)Z"))
    private static boolean voxy$retainDepartingTerrain(CameraTransform camera, RenderSection section, float distance,
                                                        Operation<Boolean> original) {
        boolean inRange = original.call(camera, section, distance);
        var coverage = voxy$visibilityPass;
        if (coverage == null) return inRange;

        int x = section.getChunkX();
        int y = section.getChunkY();
        int z = section.getChunkZ();
        // Only keep previously drawn meshes, within one section of the normal range.
        if (!inRange && (!coverage.wasSubmitted(x, y, z)
                || !original.call(camera, section, distance + 16.0f))) return false;
        return coverage.updateVisibility(x, y, z, inRange);
    }
}
