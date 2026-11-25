package me.cortex.voxy.client.core.rendering;

import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB;
import static org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_BINDING;
import static org.lwjgl.opengl.GL32C.GL_PROVOKING_VERTEX;
import static org.lwjgl.opengl.GL32C.glProvokingVertex;
import static org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.GL_DRAW_INDIRECT_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43C.*;

public final class ShadowRenderState {
    private ShadowRenderState() {}

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
        int[] storage = new int[11];
        for (int i = 0; i < storage.length; i++) storage[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        int genericStorage = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        int uniform = glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 0);
        int genericUniform = glGetInteger(GL_UNIFORM_BUFFER_BINDING);
        int[] unpackFields = {GL_UNPACK_ROW_LENGTH, GL_UNPACK_IMAGE_HEIGHT, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS};
        int[] unpackValues = new int[unpackFields.length];
        for (int i = 0; i < unpackFields.length; i++) unpackValues[i] = glGetInteger(unpackFields[i]);
        try {
            glDisable(GL_STENCIL_TEST);
            draw.run();
        } finally {
            for (int i = 0; i < unpackFields.length; i++) glPixelStorei(unpackFields[i], unpackValues[i]);
            for (int i = 0; i < storage.length; i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, storage[i]);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, genericStorage);
            glBindBufferBase(GL_UNIFORM_BUFFER, 0, uniform);
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
