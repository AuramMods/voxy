package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL11;

import static org.lwjgl.opengl.ARBDirectStateAccess.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33C.glDeleteSamplers;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL42C.*;
import static org.lwjgl.opengl.GL43C.glDispatchCompute;
import static org.lwjgl.opengl.GL45C.glTextureBarrier;

public class HiZBuffer {
    private final Shader hiz;
    private final GlFramebuffer fb = new GlFramebuffer().name("HiZ");
    private final int sampler = glGenSamplers();
    private final int type;
    private GlTexture texture;
    private GlTexture depthSeed;
    private Shader reducer;
    private boolean triedReducer;
    private int levels;
    private int width;
    private int height;
    private final RenderProperties properties;

    public HiZBuffer(RenderProperties properties) {
        this(properties, GL_DEPTH24_STENCIL8);
    }
    public HiZBuffer(RenderProperties properties, int type) {
        glNamedFramebufferDrawBuffer(this.fb.id, GL_NONE);
        this.type = type;
        this.hiz = Shader.make()
                .apply(properties::apply)
                .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
                .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh")
                .compile()
                .name("HiZ Builder");
        this.properties = properties;
    }

    private void alloc(int width, int height) {
        this.levels = Math.max(1, Integer.numberOfTrailingZeros(Math.max(width, height)));
        //We dont care about e.g. 1x1 size texture since you dont get meshlets that big to cover such a large area
        //this.levels -= 1;//Arbitrary size, shinks the max level by alot and saves a significant amount of processing time
        // (could probably increase it to be defined by a max meshlet coverage computation thing)

        //GL_DEPTH_COMPONENT32F //Cant use this as it does not match the depth format of the provided depth buffer
        if (!this.triedReducer && this.levels > 1 && Capabilities.INSTANCE.isGla) {
            this.triedReducer = true;
            try {
                this.reducer = Shader.make().add(ShaderType.COMPUTE, "voxy:hiz/reduce2.comp").compile()
                        .name("HiZ two-level reduction");
            } catch (RuntimeException failure) {
                Logger.warn("Using raster Hi-Z because the compute reduction could not compile", failure);
            }
        }
        if (this.reducer != null && this.levels > 1) {
            this.depthSeed = new GlTexture().store(this.type, 1, width, height).name("HiZ depth seed");
        }
        this.texture = new GlTexture().store(this.depthSeed == null ? this.type : GL_R32F,
                this.levels, width, height).name("HiZ");
        glTextureParameteri(this.texture.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTextureParameteri(this.texture.id, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTextureParameteri(this.texture.id, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glSamplerParameteri(this.sampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.sampler, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.sampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        this.width  = width;
        this.height = height;

        this.fb.bind(GL_DEPTH_ATTACHMENT, this.depthSeed == null ? this.texture : this.depthSeed, 0).verify();
    }

    public void buildMipChain(int srcDepthTex, int width, int height) {
        if (this.width != Integer.highestOneBit(width) || this.height != Integer.highestOneBit(height)) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            if (this.depthSeed != null) {
                this.depthSeed.free();
                this.depthSeed = null;
            }
            this.alloc(Integer.highestOneBit(width), Integer.highestOneBit(height));
        }
        glBindVertexArray(GlVertexArray.STATIC_VAO);
        int boundFB = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        this.hiz.bind();
        glBindFramebuffer(GL_FRAMEBUFFER, this.fb.id);

        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);


        glBindTextureUnit(0, srcDepthTex);
        glBindSampler(0, this.sampler);
        glUniform1i(0, 0);
        int cw = this.width;
        int ch = this.height;
        if (this.depthSeed != null) {
            glViewport(0, 0, cw, ch);
            glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
            glBindFramebuffer(GL_FRAMEBUFFER, boundFB);
            this.reduce();
            this.hiz.bind();
        } else {
            for (int i = 0; i < this.levels; i++) {
                this.fb.bind(GL_DEPTH_ATTACHMENT, this.texture, i);
                glViewport(0, 0, cw, ch); cw = Math.max(cw/2, 1); ch = Math.max(ch/2, 1);
                glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
                glTextureBarrier();
                glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT);
                glTextureParameteri(this.texture.id, GL_TEXTURE_BASE_LEVEL, i);
                glTextureParameteri(this.texture.id, GL_TEXTURE_MAX_LEVEL, i);
                if (i==0) {
                    glBindTextureUnit(0, this.texture.id);
                }
            }
            glTextureParameteri(this.texture.id, GL_TEXTURE_BASE_LEVEL, 0);
            glTextureParameteri(this.texture.id, GL_TEXTURE_MAX_LEVEL, 1000);
            glBindFramebuffer(GL_FRAMEBUFFER, boundFB);
        }

        glDepthFunc(this.properties.closerEqualDepthCompare());
        glDisable(GL_DEPTH_TEST);
        glViewport(0, 0, width, height);
        glBindVertexArray(0);
    }

    private void reduce() {
        try (var first = ImageBinding.capture(1);
             var second = ImageBinding.capture(2);
             var seed = ImageBinding.capture(3)) {
            this.reducer.bind();
            glUniform1i(1, this.properties.isReverseZ() ? 1 : 0);
            glBindTextureUnit(0, this.depthSeed.id);
            glBindImageTexture(3, this.texture.id, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            for (int level = 1; level < this.levels; level += 2) {
                boolean hasSecondLevel = level + 1 < this.levels;
                glUniform1i(0, level - 1);
                glUniform1i(2, hasSecondLevel ? 1 : 0);
                glBindImageTexture(1, this.texture.id, level, false, 0, GL_WRITE_ONLY, GL_R32F);
                glBindImageTexture(2, this.texture.id, hasSecondLevel ? level + 1 : level, false, 0, GL_WRITE_ONLY, GL_R32F);
                int groupsX = (Math.max(1, this.width >> level) + 7) / 8;
                int groupsY = (Math.max(1, this.height >> level) + 7) / 8;
                glDispatchCompute(groupsX, groupsY, 1);
                glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);
                if (level == 1) glBindTextureUnit(0, this.texture.id);
            }
        }
    }

    private record ImageBinding(int unit, int texture, int level, boolean layered,
                                int layer, int access, int format) implements AutoCloseable {
        static ImageBinding capture(int unit) {
            int name = glGetIntegeri(GL_IMAGE_BINDING_NAME, unit);
            if (name == 0) return new ImageBinding(unit, 0, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            return new ImageBinding(unit, name, glGetIntegeri(GL_IMAGE_BINDING_LEVEL, unit),
                    glGetIntegeri(GL_IMAGE_BINDING_LAYERED, unit) != 0, glGetIntegeri(GL_IMAGE_BINDING_LAYER, unit),
                    glGetIntegeri(GL_IMAGE_BINDING_ACCESS, unit), glGetIntegeri(GL_IMAGE_BINDING_FORMAT, unit));
        }

        @Override
        public void close() {
            glBindImageTexture(this.unit, this.texture, this.level, this.layered, this.layer, this.access, this.format);
        }
    }

    public void free() {
        this.fb.free();
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        if (this.depthSeed != null) this.depthSeed.free();
        if (this.reducer != null) this.reducer.free();
        glDeleteSamplers(this.sampler);
        this.hiz.free();
    }

    public int getHizTextureId() {
        return this.texture.id;
    }

    public int getPackedLevels() {
        return (this.width<<16)|this.height;//+1
    }
}
