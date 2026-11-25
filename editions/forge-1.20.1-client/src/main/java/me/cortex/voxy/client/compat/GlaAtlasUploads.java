package me.cortex.voxy.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.common.Logger;

import java.util.Locale;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

public final class GlaAtlasUploads {
    private static Boolean gla;
    private static boolean animating;
    private static boolean reported;
    private static int stagingTexture;
    private static int readFramebuffer;
    private static int drawFramebuffer;
    private static int stagingWidth;
    private static int stagingHeight;

    private GlaAtlasUploads() {}

    public static boolean isGla() {
        if (gla == null) {
            String version = glGetString(GL_VERSION);
            gla = version != null && version.toLowerCase(Locale.ROOT).contains("(gla ");
        }
        return gla;
    }

    public static void animate(Runnable tick) {
        boolean previous = animating;
        animating = true;
        try {
            tick.run();
        } finally {
            animating = previous;
        }
    }

    public static boolean upload(int target, int level, int x, int y, int width, int height,
                                 int format, int type, long pixels) {
        if (!animating || target != GL_TEXTURE_2D || format != GL_RGBA || type != GL_UNSIGNED_BYTE
                || width <= 0 || height <= 0) {
            return false;
        }
        int atlas = glGetInteger(GL_TEXTURE_BINDING_2D);
        int atlasFormat = glGetTexLevelParameteri(target, level, GL_TEXTURE_INTERNAL_FORMAT);
        if (atlas == 0 || (atlasFormat != GL_RGBA && atlasFormat != GL_RGBA8)) {
            return false;
        }

        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissored = glIsEnabled(GL_SCISSOR_TEST);
        allocate();
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, atlas, level);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            glBindTexture(target, stagingTexture);
            if (width > stagingWidth || height > stagingHeight) {
                stagingWidth = Math.max(stagingWidth, width);
                stagingHeight = Math.max(stagingHeight, height);
                nglTexImage2D(target, 0, GL_RGBA8, stagingWidth, stagingHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
                glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            }
            // NativeImage has already set the row length, alignment and frame offsets.
            GlStateManager._texSubImage2D(target, 0, 0, 0, width, height, format, type, pixels);

            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, stagingTexture, 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            if (scissored) glDisable(GL_SCISSOR_TEST);
            // Keep the large atlas on the GPU when each animation changes only a sprite.
            glBlitFramebuffer(0, 0, width, height, x, y, x + width, y + height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            if (!reported) {
                Logger.info("Using GPU copies for animated block atlas uploads on GLA");
                reported = true;
            }
            return true;
        } finally {
            if (scissored) glEnable(GL_SCISSOR_TEST);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, 0, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            glBindTexture(target, atlas);
        }
    }

    private static void allocate() {
        if (stagingTexture != 0) return;
        stagingTexture = glGenTextures();
        readFramebuffer = glGenFramebuffers();
        drawFramebuffer = glGenFramebuffers();
    }

    public static void close() {
        if (stagingTexture == 0) return;
        glDeleteFramebuffers(readFramebuffer);
        glDeleteFramebuffers(drawFramebuffer);
        glDeleteTextures(stagingTexture);
        stagingTexture = 0;
        stagingWidth = 0;
        stagingHeight = 0;
    }
}
