package me.cortex.voxy.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.common.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

public final class GlaAtlasUploads {
    private static final int MAX_FRAME_TEXTURES = 2048;
    private static final long MAX_FRAME_BYTES = 32L << 20;
    private static final Map<FrameKey, Integer> FRAME_TEXTURES = new HashMap<>();

    private static Boolean gla;
    private static int animationAtlas;
    private static int cachedAtlas;
    private static Object frameSprite;
    private static int frameIndex;
    private static long frameBytes;
    private static boolean reported;
    private static int stagingTexture;
    private static int readFramebuffer;
    private static int drawFramebuffer;
    private static int stagingWidth;
    private static int stagingHeight;

    private record FrameKey(Object sprite, int frame, int level, int width, int height) {}

    private GlaAtlasUploads() {}

    public static boolean isGla() {
        if (gla == null) {
            String version = glGetString(GL_VERSION);
            gla = version != null && version.toLowerCase(Locale.ROOT).contains("(gla ");
        }
        return gla;
    }

    public static void animate(int atlas, Runnable tick) {
        if (cachedAtlas != atlas) {
            invalidateAtlas();
            cachedAtlas = atlas;
        }
        int previous = animationAtlas;
        animationAtlas = atlas;
        try {
            tick.run();
        } finally {
            animationAtlas = previous;
        }
    }

    public static boolean isAnimating() {
        return animationAtlas != 0;
    }

    public static void immutableFrame(Object sprite, int frame, Runnable upload) {
        Object previousSprite = frameSprite;
        int previousFrame = frameIndex;
        frameSprite = sprite;
        frameIndex = frame;
        try {
            upload.run();
        } finally {
            frameSprite = previousSprite;
            frameIndex = previousFrame;
        }
    }

    public static boolean upload(int target, int level, int x, int y, int width, int height,
                                 int format, int type, long pixels) {
        if (animationAtlas == 0 || target != GL_TEXTURE_2D || format != GL_RGBA || type != GL_UNSIGNED_BYTE
                || width <= 0 || height <= 0) {
            return false;
        }
        int atlas = glGetInteger(GL_TEXTURE_BINDING_2D);
        if (atlas != animationAtlas) return false;
        int atlasFormat = glGetTexLevelParameteri(target, level, GL_TEXTURE_INTERNAL_FORMAT);
        if (atlasFormat != GL_RGBA && atlasFormat != GL_RGBA8) {
            return false;
        }

        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissored = glIsEnabled(GL_SCISSOR_TEST);
        allocate();
        int pendingTexture = 0;
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, atlas, level);
            if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            // Keep recurring source frames immutable on the GPU. NativeImage has already set the unpack layout.
            FrameKey key = frameSprite == null ? null : new FrameKey(frameSprite, frameIndex, level, width, height);
            Integer cached = key == null ? null : FRAME_TEXTURES.get(key);
            int source;
            long bytes = (long) width * height * 4;
            if (cached != null) {
                source = cached;
            } else if (key != null && FRAME_TEXTURES.size() < MAX_FRAME_TEXTURES && frameBytes + bytes <= MAX_FRAME_BYTES) {
                pendingTexture = glGenTextures();
                source = pendingTexture;
                glBindTexture(target, source);
                defineTexture(width, height, pixels);
            } else {
                source = stagingTexture;
                uploadTransient(width, height, pixels);
            }

            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, source, 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            if (pendingTexture != 0) {
                FRAME_TEXTURES.put(key, pendingTexture);
                frameBytes += bytes;
                pendingTexture = 0;
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
            if (pendingTexture != 0) glDeleteTextures(pendingTexture);
            if (scissored) glEnable(GL_SCISSOR_TEST);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, 0, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
            glBindTexture(target, atlas);
        }
    }

    private static void uploadTransient(int width, int height, long pixels) {
        glBindTexture(GL_TEXTURE_2D, stagingTexture);
        if (width > stagingWidth || height > stagingHeight) {
            stagingWidth = Math.max(stagingWidth, width);
            stagingHeight = Math.max(stagingHeight, height);
            defineTexture(stagingWidth, stagingHeight, 0);
        }
        GlStateManager._texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    private static void defineTexture(int width, int height, long pixels) {
        nglTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    public static void invalidateAtlas() {
        cachedAtlas = 0;
        if (FRAME_TEXTURES.isEmpty()) return;
        int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
        FRAME_TEXTURES.values().forEach(texture -> glDeleteTextures(texture));
        FRAME_TEXTURES.clear();
        frameBytes = 0;
    }

    private static void allocate() {
        if (stagingTexture != 0) return;
        stagingTexture = glGenTextures();
        readFramebuffer = glGenFramebuffers();
        drawFramebuffer = glGenFramebuffers();
    }

    public static void close() {
        invalidateAtlas();
        if (stagingTexture == 0) return;
        glDeleteFramebuffers(readFramebuffer);
        glDeleteFramebuffers(drawFramebuffer);
        glDeleteTextures(stagingTexture);
        stagingTexture = 0;
        stagingWidth = 0;
        stagingHeight = 0;
    }
}
