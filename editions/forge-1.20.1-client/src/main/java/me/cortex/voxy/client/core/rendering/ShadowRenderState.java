package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_BINDING;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_SIZE;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_START;
import static org.lwjgl.opengl.GL32C.GL_PROVOKING_VERTEX;
import static org.lwjgl.opengl.GL32C.glGetInteger64i;
import static org.lwjgl.opengl.GL32C.glProvokingVertex;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43C.*;

public final class ShadowRenderState {
    private static final int[] UNPACK_FIELDS = {
            GL_UNPACK_ROW_LENGTH, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_PIXELS,
            GL_UNPACK_SKIP_ROWS, GL_UNPACK_SKIP_IMAGES
    };

    private ShadowRenderState() {}

    private record BufferBinding(int buffer, long offset, long size) {
        static BufferBinding capture(int index, int binding, int start, int size) {
            int buffer = glGetIntegeri(binding, index);
            if (buffer == 0) return new BufferBinding(0, 0, 0);
            return new BufferBinding(buffer, glGetInteger64i(start, index), glGetInteger64i(size, index));
        }

        void restore(int target, int index) {
            if (this.size != 0) glBindBufferRange(target, index, this.buffer, this.offset, this.size);
            else glBindBufferBase(target, index, this.buffer);
        }
    }

    public static void run(Runnable draw) {
        int program = glGetInteger(GL_CURRENT_PROGRAM);
        int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        int vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int provoking = glGetInteger(GL_PROVOKING_VERTEX);
        int drawIndirect = glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING);
        int dispatchIndirect = glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        int parameterBuffer = glGetInteger(GL_PARAMETER_BUFFER_BINDING_ARB);
        int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        glActiveTexture(activeTexture);
        int sampler = glGetIntegeri(GL_SAMPLER_BINDING, 0);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean cullFace = glIsEnabled(GL_CULL_FACE);
        boolean stencil = glIsEnabled(GL_STENCIL_TEST);
        BufferBinding[] storage = new BufferBinding[11];
        for (int i = 0; i < storage.length; i++) {
            storage[i] = BufferBinding.capture(i, GL_SHADER_STORAGE_BUFFER_BINDING,
                    GL_SHADER_STORAGE_BUFFER_START, GL_SHADER_STORAGE_BUFFER_SIZE);
        }
        BufferBinding printf = PrintfDebugUtil.ENABLE_PRINTF_DEBUGGING
                ? BufferBinding.capture(20, GL_SHADER_STORAGE_BUFFER_BINDING,
                    GL_SHADER_STORAGE_BUFFER_START, GL_SHADER_STORAGE_BUFFER_SIZE) : null;
        int genericStorage = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        var drawUniform = BufferBinding.capture(0, GL_UNIFORM_BUFFER_BINDING, GL_UNIFORM_BUFFER_START, GL_UNIFORM_BUFFER_SIZE);
        var traversalUniform = BufferBinding.capture(1, GL_UNIFORM_BUFFER_BINDING, GL_UNIFORM_BUFFER_START, GL_UNIFORM_BUFFER_SIZE);
        int genericUniform = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
        int[] unpackValues = new int[UNPACK_FIELDS.length];
        for (int i = 0; i < UNPACK_FIELDS.length; i++) unpackValues[i] = glGetInteger(UNPACK_FIELDS[i]);
        try {
            glDisable(GL_STENCIL_TEST);
            draw.run();
        } finally {
            for (int i = 0; i < UNPACK_FIELDS.length; i++) glPixelStorei(UNPACK_FIELDS[i], unpackValues[i]);
            for (int i = 0; i < storage.length; i++) storage[i].restore(GL_SHADER_STORAGE_BUFFER, i);
            if (printf != null) printf.restore(GL_SHADER_STORAGE_BUFFER, 20);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, genericStorage);
            drawUniform.restore(GL_UNIFORM_BUFFER, 0);
            traversalUniform.restore(GL_UNIFORM_BUFFER, 1);
            glBindBuffer(GL_UNIFORM_BUFFER, genericUniform);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER, drawIndirect);
            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, dispatchIndirect);
            glBindBuffer(GL_PARAMETER_BUFFER_ARB, parameterBuffer);
            glBindVertexArray(vao);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            glUseProgram(program);
            glProvokingVertex(provoking);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindSampler(0, sampler);
            glActiveTexture(activeTexture);
            glDepthMask(depthWrite);
            glDepthFunc(depthFunc);
            if (depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (cullFace) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
            if (stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
        }
    }
}
