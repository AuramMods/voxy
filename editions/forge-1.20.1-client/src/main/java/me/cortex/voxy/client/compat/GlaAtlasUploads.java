package me.cortex.voxy.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.core.gl.GlFence;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43C.glCopyImageSubData;

public final class GlaAtlasUploads {
    private static final int MAX_FRAME_TEXTURES = 2048;
    private static final long MAX_FRAME_BYTES = 32L << 20;
    private static final Map<FrameKey, Integer> FRAME_TEXTURES = new HashMap<>();
    private static final UploadBatch BATCH = new UploadBatch();

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
        BATCH.flush();
        if (cachedAtlas != atlas) {
            invalidateAtlas();
            cachedAtlas = atlas;
        }
        int previous = animationAtlas;
        animationAtlas = atlas;
        try {
            tick.run();
        } finally {
            try {
                BATCH.flush();
            } finally {
                animationAtlas = previous;
            }
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
                                 int format, int type, long pixels, int imageWidth, int skipX, int skipY,
                                 boolean closesImage) {
        if (animationAtlas == 0 || target != GL_TEXTURE_2D || format != GL_RGBA || type != GL_UNSIGNED_BYTE
                || width <= 0 || height <= 0) {
            BATCH.flush();
            return false;
        }
        int atlas = glGetInteger(GL_TEXTURE_BINDING_2D);
        if (atlas != animationAtlas) {
            BATCH.flush();
            return false;
        }
        int atlasFormat = glGetTexLevelParameteri(target, level, GL_TEXTURE_INTERNAL_FORMAT);
        if (atlasFormat != GL_RGBA && atlasFormat != GL_RGBA8) {
            BATCH.flush();
            return false;
        }
        FrameKey key = frameSprite == null ? null : new FrameKey(frameSprite, frameIndex, level, width, height);
        Integer cached = key == null ? null : FRAME_TEXTURES.get(key);
        // Cache hits can copy immediately. Group only uploads that need CPU pixel transfers.
        if (cached == null && !closesImage && pixels != 0 && width <= UploadBatch.SIZE && height <= UploadBatch.SIZE
                && glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING) == 0) {
            BATCH.add(level, x, y, width, height, pixels, imageWidth, skipX, skipY, key);
            return true;
        }

        BATCH.flush();
        if (cached != null && atlasFormat == GL_RGBA8) {
            glCopyImageSubData(cached, target, 0, 0, 0, 0,
                    atlas, target, level, x, y, 0, width, height, 1);
            reportGpuCopies();
            return true;
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
            reportGpuCopies();
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

    private static void reportGpuCopies() {
        if (!reported) {
            Logger.info("Using GPU copies for animated block atlas uploads on GLA");
            reported = true;
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
        BATCH.flush();
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
        BATCH.close();
        if (stagingTexture == 0) return;
        glDeleteFramebuffers(readFramebuffer);
        glDeleteFramebuffers(drawFramebuffer);
        glDeleteTextures(stagingTexture);
        stagingTexture = 0;
        stagingWidth = 0;
        stagingHeight = 0;
    }

    private static final class UploadBatch {
        private static final int SIZE = 512;
        private static final int MAX_COPIES = 1024;
        private static final int STRIDE = SIZE * 4;
        private final StagingPage[] pages = {new StagingPage(), new StagingPage(), new StagingPage()};
        private final List<Copy> copies = new ArrayList<>();
        private long pixels;
        private int nextX, nextY, rowHeight, usedWidth, usedHeight;
        private int attachedRead, attachedDraw, attachedLevel;
        private boolean readComplete, drawComplete;

        private record Copy(int level, int x, int y, int width, int height, FrameKey key,
                            int stagingX, int stagingY) {}

        private static final class StagingPage {
            int texture;
            GlFence fence;

            boolean available() {
                if (this.fence == null) return true;
                if (!this.fence.signaled()) return false;
                this.fence.free();
                this.fence = null;
                return true;
            }

            void close() {
                if (this.fence != null) this.fence.free();
                if (this.texture != 0) glDeleteTextures(this.texture);
                this.fence = null;
                this.texture = 0;
            }
        }

        void add(int level, int x, int y, int width, int height, long source, int stride, int skipX, int skipY,
                 FrameKey key) {
            if (this.copies.size() == MAX_COPIES) this.flush();

            if (this.nextX + width > SIZE) {
                this.nextX = 0;
                this.nextY += this.rowHeight;
                this.rowHeight = 0;
            }
            if (this.nextY + height > SIZE) this.flush();
            if (this.pixels == 0) this.pixels = MemoryUtil.nmemAllocChecked((long) SIZE * STRIDE);
            long read = source + ((long) skipY * stride + skipX) * 4;
            long write = this.pixels + (long) this.nextY * STRIDE + this.nextX * 4L;
            for (int row = 0; row < height; row++) {
                MemoryUtil.memCopy(read + row * stride * 4L, write + row * STRIDE, width * 4L);
            }
            this.copies.add(new Copy(level, x, y, width, height, key, this.nextX, this.nextY));
            this.nextX += width;
            this.rowHeight = Math.max(this.rowHeight, height);
            this.usedWidth = Math.max(this.usedWidth, this.nextX);
            this.usedHeight = Math.max(this.usedHeight, this.nextY + height);
        }

        void flush() {
            if (this.copies.isEmpty()) return;
            allocate();
            int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
            int previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            int previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            int previousUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
            int rowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
            int skipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
            int skipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
            int alignment = glGetInteger(GL_UNPACK_ALIGNMENT);
            boolean scissored = glIsEnabled(GL_SCISSOR_TEST);
            StagingPage page = null;
            try {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
                if (scissored) glDisable(GL_SCISSOR_TEST);
                this.attachedRead = this.attachedDraw = 0;
                if (this.usedWidth > 0) page = this.stage();
                for (Copy copy : this.copies) this.copyToAtlas(copy, page);
                reportGpuCopies();
            } finally {
                if (page != null) page.fence = new GlFence();
                if (scissored) glEnable(GL_SCISSOR_TEST);
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw);
                glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead);
                glBindTexture(GL_TEXTURE_2D, previousTexture);
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, previousUnpackBuffer);
                setUnpack(rowLength, skipPixels, skipRows, alignment);
                this.copies.clear();
                this.nextX = this.nextY = this.rowHeight = this.usedWidth = this.usedHeight = 0;
            }
        }

        private StagingPage stage() {
            for (StagingPage page : this.pages) {
                if (!page.available()) continue;
                setUnpack(SIZE, 0, 0, 4);
                if (page.texture == 0) {
                    page.texture = glGenTextures();
                    glBindTexture(GL_TEXTURE_2D, page.texture);
                    defineTexture(SIZE, SIZE, 0);
                } else {
                    glBindTexture(GL_TEXTURE_2D, page.texture);
                }
                GlStateManager._texSubImage2D(GL_TEXTURE_2D, 0, 0, 0, this.usedWidth, this.usedHeight,
                        GL_RGBA, GL_UNSIGNED_BYTE, this.pixels);
                return page;
            }
            return null;
        }

        private void copyToAtlas(Copy copy, StagingPage page) {
            int texture;
            int sx = 0, sy = 0;
            if (page != null) {
                texture = page.texture;
                sx = copy.stagingX();
                sy = copy.stagingY();
            } else {
                setUnpack(SIZE, 0, 0, 4);
                uploadTransient(copy.width(), copy.height(), this.stagedPixels(copy));
                texture = stagingTexture;
            }

            if (!this.blit(texture, sx, sy, animationAtlas, copy.level(), copy.x(), copy.y(), copy.width(), copy.height())) {
                glBindTexture(GL_TEXTURE_2D, animationAtlas);
                setUnpack(SIZE, 0, 0, 4);
                GlStateManager._texSubImage2D(GL_TEXTURE_2D, copy.level(), copy.x(), copy.y(), copy.width(), copy.height(),
                        GL_RGBA, GL_UNSIGNED_BYTE, this.stagedPixels(copy));
                return;
            }

            long bytes = (long) copy.width() * copy.height() * 4;
            if (copy.key() != null && !FRAME_TEXTURES.containsKey(copy.key())
                    && FRAME_TEXTURES.size() < MAX_FRAME_TEXTURES && frameBytes + bytes <= MAX_FRAME_BYTES) {
                int cached = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, cached);
                defineTexture(copy.width(), copy.height(), 0);
                if (this.blit(texture, sx, sy, cached, 0, 0, 0, copy.width(), copy.height())) {
                    FRAME_TEXTURES.put(copy.key(), cached);
                    frameBytes += bytes;
                } else {
                    glDeleteTextures(cached);
                    this.attachedDraw = 0;
                }
            }
        }

        private boolean blit(int source, int sx, int sy, int dest, int level, int x, int y, int width, int height) {
            if (this.attachedRead != source) {
                glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, source, 0);
                this.attachedRead = source;
                this.readComplete = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
            }
            if (!this.readComplete) return false;
            if (this.attachedDraw != dest || this.attachedLevel != level) {
                glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dest, level);
                this.attachedDraw = dest;
                this.attachedLevel = level;
                this.drawComplete = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
            }
            if (!this.drawComplete) return false;
            glBlitFramebuffer(sx, sy, sx + width, sy + height, x, y, x + width, y + height,
                    GL_COLOR_BUFFER_BIT, GL_NEAREST);
            return true;
        }

        private long stagedPixels(Copy copy) {
            return this.pixels + (long) copy.stagingY() * STRIDE + copy.stagingX() * 4L;
        }

        private static void setUnpack(int rowLength, int skipX, int skipY, int alignment) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, skipX);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, skipY);
            glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
        }

        void close() {
            for (StagingPage page : this.pages) page.close();
            if (this.pixels != 0) MemoryUtil.nmemFree(this.pixels);
            this.pixels = 0;
        }
    }
}
