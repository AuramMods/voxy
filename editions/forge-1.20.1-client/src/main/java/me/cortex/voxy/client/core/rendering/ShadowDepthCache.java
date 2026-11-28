package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.hierachical.ShadowCasterChanges;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.network.ClientLodStream;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.common.util.ExceptionCleanup;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform3i;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GL45C.*;

public final class ShadowDepthCache implements AutoCloseable {
    private final RenderProperties properties;
    private final Matrix4f lightProjection = new Matrix4f();
    private final Matrix4f lightView = new Matrix4f();
    private final Matrix4f cameraProjection = new Matrix4f();
    private final Matrix4f cameraView = new Matrix4f();
    private final float[] clear = {1.0f};
    private GlFramebuffer framebuffer;
    private GlTexture depth;
    private FullscreenBlit merge;
    private FullscreenBlit clearTiles;
    private int target, size, format, curvature;
    private float casterRange;
    private double x, y, z;
    private long nearRevision = -1;
    private long atlasRevision = -1;
    private float lodRadius;
    private boolean streamed;
    private GlBuffer mask;
    private GlBuffer regions;
    private Shader markTiles;
    private final float[] transform = new float[16];
    private boolean cached;
    private boolean stableLastFrame;

    public ShadowDepthCache(RenderProperties properties) {
        this.properties = properties;
        if (properties.closerDepthCompare() == GL_GREATER) this.clear[0] = 0.0f;
    }

    /**
     * Draw Voxy's depth separately so that a cache hit can be combined with fresh
     * vanilla terrain and moving casters. False means the caller draws normally.
     */
    public boolean draw(MDICViewport viewport, int target, GlFramebuffer destination, ShadowCasterChanges changes,
                        long nearRevision, long atlasRevision, MDICSectionRenderer renderer, String shadowSource) {
        try {
            return this.drawAndUpdate(viewport, target, destination, changes, nearRevision, atlasRevision, renderer, shadowSource);
        } finally {
            changes.clear();
        }
    }

    private boolean drawAndUpdate(MDICViewport viewport, int target, GlFramebuffer destination, ShadowCasterChanges changes,
                                  long nearRevision, long atlasRevision, MDICSectionRenderer renderer, String shadowSource) {
        if (glIsEnabled(GL_SCISSOR_TEST) || glIsEnabled(GL_POLYGON_OFFSET_FILL)
                || (this.properties.closerDepthCompare() != GL_LESS && this.properties.closerDepthCompare() != GL_GREATER)) {
            this.cached = false;
            return false;
        }

        var camera = CapturedRenderingState.INSTANCE;
        int curvature = VoxyConfig.CONFIG.earthCurveRatio;
        boolean streamed = ClientLodStream.hasScope();
        float lodRadius = ClientLodStream.clampRadius(VoxyConfig.CONFIG.getLodRenderDistanceBlocks());
        boolean sameTransform = target == this.target && viewport.width == this.size
                && this.nearRevision == nearRevision && this.atlasRevision == atlasRevision
                && viewport.shadowCasterRange == this.casterRange && this.curvature == curvature
                && this.streamed == streamed && this.lodRadius == lodRadius
                && viewport.cameraX == this.x && viewport.cameraY == this.y && viewport.cameraZ == this.z
                && this.lightProjection.equals(viewport.vanillaProjection) && this.lightView.equals(viewport.modelView)
                && this.cameraProjection.equals(camera.getGbufferProjection())
                && this.cameraView.equals(camera.getGbufferModelView());
        if (!sameTransform) {
            if (target != this.target || viewport.width != this.size) {
                this.releaseDepth();
                this.format = glGetTextureLevelParameteri(target, 0, GL_TEXTURE_INTERNAL_FORMAT);
            }
            this.target = target;
            this.size = viewport.width;
            this.streamed = streamed;
            this.lodRadius = lodRadius;
            this.nearRevision = nearRevision;
            this.atlasRevision = atlasRevision;
            this.curvature = curvature;
            this.casterRange = viewport.shadowCasterRange;
            this.x = viewport.cameraX;
            this.y = viewport.cameraY;
            this.z = viewport.cameraZ;
            this.lightProjection.set(viewport.vanillaProjection);
            this.lightView.set(viewport.modelView);
            this.cameraProjection.set(camera.getGbufferProjection());
            this.cameraView.set(camera.getGbufferModelView());
            this.cached = false;
        }

        if (this.format != GL_DEPTH_COMPONENT24 && this.format != GL_DEPTH_COMPONENT32F
                && this.format != GL_DEPTH_COMPONENT32 && this.format != GL_DEPTH_COMPONENT16) return false;
        boolean buildCache = sameTransform || this.stableLastFrame;
        this.stableLastFrame = sameTransform;
        if (!buildCache) return false;
        if (changes.requiresFullRefresh()) this.cached = false;
        if (!this.cached) {
            this.allocate();
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.framebuffer.id);
            glDepthMask(true);
            glClearNamedFramebufferfv(this.framebuffer.id, GL_DEPTH, 0, this.clear);
            renderer.renderShadowCasters(viewport);
            this.cached = true;
        } else if (changes.regionCount() != 0) {
            if (curvature != 0) {
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.framebuffer.id);
                glDepthMask(true);
                glClearNamedFramebufferfv(this.framebuffer.id, GL_DEPTH, 0, this.clear);
                renderer.renderShadowCasters(viewport);
            } else {
                this.updateTiles(viewport, changes, renderer, shadowSource);
            }
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, destination.id);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(this.properties.closerDepthCompare());
        glDisable(GL_CULL_FACE);
        glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
        glBindTextureUnit(0, this.depth.id);
        glBindSampler(0, 0);
        this.merge.bind();
        this.merge.blit();
        return true;
    }

    private void updateTiles(MDICViewport viewport, ShadowCasterChanges changes, MDICSectionRenderer renderer,
                             String shadowSource) {
        if (this.markTiles == null) {
            String source = ShaderLoader.parse("voxy:lod/gl46/mark_shadow_tiles.comp") + "\n" + shadowSource;
            this.markTiles = Shader.make().addSource(ShaderType.COMPUTE, source).compile();
            this.clearTiles = new FullscreenBlit(this.properties, "voxy:post/clear_shadow_tiles.frag");
            this.mask = new GlBuffer(16L * 16 * 4).name("Voxy dirty shadow tiles");
            this.regions = new GlBuffer(ShadowCasterChanges.MAX_REGIONS * 16L).name("Voxy changed shadow sections");
        }
        this.mask.zero();
        int count = changes.regionCount();
        changes.writeRegions(UploadStream.INSTANCE.upload(this.regions, 0, count * 16));
        UploadStream.INSTANCE.commit();
        var light = viewport.vanillaProjection.mul(viewport.modelView, new Matrix4f());
        light.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        this.markTiles.bind();
        glUniformMatrix4fv(0, false, light.get(this.transform));
        glUniform3i(4, viewport.section.x, viewport.section.y, viewport.section.z);
        glUniform1i(5, count);
        glUniform1i(6, this.size);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 9, this.mask.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 10, this.regions.id);
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute((count + 63) / 64, 1, 1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.framebuffer.id);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        glDepthFunc(GL_ALWAYS);
        glDisable(GL_CULL_FACE);
        this.clearTiles.bind();
        glUniform1i(0, this.size);
        glUniform1f(1, this.clear[0]);
        this.clearTiles.blit();

        viewport.shadowDirtyTiles = this.mask;
        try {
            renderer.renderShadowCasters(viewport);
        } finally {
            viewport.shadowDirtyTiles = null;
        }
    }

    private void allocate() {
        if (this.depth != null) return;
        if (this.merge == null) this.merge = new FullscreenBlit(this.properties, "voxy:post/merge_shadow_depth.frag");
        if (this.framebuffer == null) {
            this.framebuffer = new GlFramebuffer().name("Voxy static shadow depth");
            glNamedFramebufferDrawBuffer(this.framebuffer.id, GL_NONE);
            glNamedFramebufferReadBuffer(this.framebuffer.id, GL_NONE);
        }
        this.depth = new GlTexture().store(this.format, 1, this.size, this.size).name("Voxy cached shadow depth");
        glTextureParameteri(this.depth.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTextureParameteri(this.depth.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glNamedFramebufferTexture(this.framebuffer.id, GL_DEPTH_ATTACHMENT, this.depth.id, 0);
        this.framebuffer.verify();
    }

    private void releaseDepth() {
        if (this.depth == null) return;
        glNamedFramebufferTexture(this.framebuffer.id, GL_DEPTH_ATTACHMENT, 0, 0);
        this.depth.free();
        this.depth = null;
    }

    @Override
    public void close() {
        var cleanup = new ExceptionCleanup(null);
        cleanup.run(this::releaseDepth);
        if (this.framebuffer != null) cleanup.run(this.framebuffer::free);
        if (this.merge != null) cleanup.run(this.merge::delete);
        if (this.clearTiles != null) cleanup.run(this.clearTiles::delete);
        if (this.mask != null) cleanup.run(this.mask::free);
        if (this.regions != null) cleanup.run(this.regions::free);
        if (this.markTiles != null) cleanup.run(this.markTiles::free);
        cleanup.rethrow();
    }
}
