package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;

public class MDICViewport extends Viewport<MDICViewport> {
    public final GlBuffer drawCountCallBuffer = new GlBuffer(1024).zero();
    public final GlBuffer drawCallBuffer;
    public final GlBuffer positionScratchBuffer;
    public final GlBuffer indirectLookupBuffer;
    public final GlBuffer visibilityBuffer;

    public MDICViewport(RenderProperties properties, int maxSectionCount) {
        this(properties, maxSectionCount, false);
    }

    MDICViewport(RenderProperties properties, int maxSectionCount, boolean shadowPass) {
        super(properties);
        int queueCapacity = shadowPass ? HierarchicalOcclusionTraverser.MAX_SHADOW_SECTIONS
                : HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE;
        int drawCapacity = shadowPass ? queueCapacity * 7 : MDICSectionRenderer.OPAQUE_DRAW_COUNT
                + MDICSectionRenderer.TRANSLUCENT_DRAW_COUNT + MDICSectionRenderer.TEMPORAL_DRAW_COUNT;
        this.drawCallBuffer = new GlBuffer(20L * drawCapacity).zero();
        this.positionScratchBuffer = new GlBuffer(8L * (shadowPass ? queueCapacity : 400_000)).zero();
        this.indirectLookupBuffer = new GlBuffer(4L * (queueCapacity + 1));
        // The shared command shader requires a binding here even though shadows skip eye visibility.
        this.visibilityBuffer = new GlBuffer(shadowPass ? 4 : maxSectionCount * 4L);
    }

    @Override
    protected void delete0() {
        super.delete0();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public GlBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}
