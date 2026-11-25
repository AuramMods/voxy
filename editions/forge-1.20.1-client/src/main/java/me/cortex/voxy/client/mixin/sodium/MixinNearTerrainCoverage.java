package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.NearTerrainCoverage;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MixinNearTerrainCoverage {
    @Unique private static NearTerrainCoverage voxy$solidPass;

    @Inject(method = "render", at = @At("HEAD"))
    private void voxy$beginSolidPass(ChunkRenderMatrices matrices, CommandList commandList,
                                     ChunkRenderListIterable lists, TerrainRenderPass pass,
                                     CameraTransform camera, CallbackInfo ci) {
        voxy$solidPass = null;
        if (pass != DefaultTerrainRenderPasses.SOLID || IrisUtil.irisShadowActive()) return;
        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if (renderer != null) {
            voxy$solidPass = renderer.nearTerrainCoverage;
            voxy$solidPass.beginSolidPass();
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void voxy$endSolidPass(CallbackInfo ci) {
        voxy$solidPass = null;
    }

    @Inject(method = "fillCommandBuffer", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/DefaultChunkRenderer;addDrawCommands(Lme/jellysquid/mods/sodium/client/gl/device/MultiDrawBatch;JII)V"))
    private static void voxy$recordSection(
            CallbackInfo ci, @Local(argsOnly = true) RenderRegion region, @Local(ordinal = 4) int sectionIndex) {
        if (voxy$solidPass == null) return;
        voxy$solidPass.submit(region.getChunkX() + LocalSectionIndex.unpackX(sectionIndex),
                region.getChunkY() + LocalSectionIndex.unpackY(sectionIndex),
                region.getChunkZ() + LocalSectionIndex.unpackZ(sectionIndex));
    }
}
