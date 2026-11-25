package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.NearTerrainCoverage;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MixinNearTerrainCoverage {
    @Unique private static NearTerrainCoverage voxy$opaquePass;

    @Shadow
    private static int getVisibleFaces(int cameraX, int cameraY, int cameraZ, int x, int y, int z) {
        throw new AssertionError();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void voxy$beginTerrainPass(ChunkRenderMatrices matrices, CommandList commandList,
                                       ChunkRenderListIterable lists, TerrainRenderPass pass,
                                       CameraTransform camera, CallbackInfo ci) {
        voxy$opaquePass = null;
        if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) return;
        boolean shadow = IrisUtil.irisShadowActive();
        var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).voxy$getRenderSystem();
        if (renderer == null) return;

        if (shadow) {
            voxy$opaquePass = renderer.getShadowTerrainCoverage();
        } else if (pass == DefaultTerrainRenderPasses.SOLID) {
            voxy$opaquePass = renderer.nearTerrainCoverage;
            voxy$opaquePass.beginSolidPass();
        } else if (pass == DefaultTerrainRenderPasses.CUTOUT) {
            voxy$collectTranslucentSections(lists, camera, renderer.nearTerrainCoverage);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void voxy$endTerrainPass(CallbackInfo ci) {
        voxy$opaquePass = null;
    }

    @Inject(method = "fillCommandBuffer", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/DefaultChunkRenderer;addDrawCommands(Lme/jellysquid/mods/sodium/client/gl/device/MultiDrawBatch;JII)V"))
    private static void voxy$recordSection(
            CallbackInfo ci, @Local(argsOnly = true) RenderRegion region, @Local(ordinal = 4) int sectionIndex) {
        if (voxy$opaquePass == null) return;
        voxy$opaquePass.submit(region.getChunkX() + LocalSectionIndex.unpackX(sectionIndex),
                region.getChunkY() + LocalSectionIndex.unpackY(sectionIndex),
                region.getChunkZ() + LocalSectionIndex.unpackZ(sectionIndex));
    }

    @Unique
    private static void voxy$collectTranslucentSections(ChunkRenderListIterable lists, CameraTransform camera,
                                                       NearTerrainCoverage coverage) {
        // Voxy draws before Embeddium's translucent pass, which reuses this visible list.
        var pass = DefaultTerrainRenderPasses.TRANSLUCENT;
        boolean cullFaces = SodiumClientMod.options().performance.useBlockFaceCulling && !pass.isSorted();
        var regions = lists.iterator();
        while (regions.hasNext()) {
            var list = regions.next();
            var region = list.getRegion();
            var storage = region.getStorage(pass);
            if (storage == null) continue;
            var sections = list.sectionsWithGeometryIterator(false);
            if (sections == null) continue;
            while (sections.hasNext()) {
                int index = sections.nextByteAsInt();
                int slices = SectionRenderDataUnsafe.getSliceMask(storage.getDataPointer(index));
                if (slices == 0) continue;
                int x = region.getChunkX() + LocalSectionIndex.unpackX(index);
                int y = region.getChunkY() + LocalSectionIndex.unpackY(index);
                int z = region.getChunkZ() + LocalSectionIndex.unpackZ(index);
                int faces = cullFaces ? getVisibleFaces(camera.intX, camera.intY, camera.intZ, x, y, z) : ModelQuadFacing.ALL;
                if ((slices & faces) != 0) coverage.submitTranslucent(x, y, z);
            }
        }
    }
}
