package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import net.minecraft.Util;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/** Solid Embeddium sections submitted in the current main terrain pass. */
public final class NearTerrainCoverage {
    private final LongOpenHashSet submitted = new LongOpenHashSet();
    private final Long2IntOpenHashMap firstSubmission = new Long2IntOpenHashMap();
    private GlBuffer buffer = new GlBuffer(48);
    private boolean initialized;

    public void beginSolidPass() {
        this.submitted.clear();
    }

    public void submit(int x, int y, int z) {
        this.submitted.add(SectionPos.asLong(x, y, z));
    }

    public void upload(Viewport<?> viewport, boolean enabled) {
        if (!enabled) this.submitted.clear();
        // Reserve zero for empty slots. Unsigned age subtraction also handles clock wrap.
        int now = (int) Util.getMillis() | 1;
        if (!this.initialized || !this.firstSubmission.keySet().equals(this.submitted)) {
            this.firstSubmission.keySet().retainAll(this.submitted);
            var sections = this.submitted.iterator();
            while (sections.hasNext()) {
                this.firstSubmission.putIfAbsent(sections.nextLong(), now);
            }
            this.upload();
            this.initialized = true;
        }
        viewport.nearTerrainSections = this.buffer;
        viewport.nearTerrainTime = now;
        // Never let a pass reuse a collection from a frame where no solid draw ran.
        this.submitted.clear();
    }

    private void upload() {
        int slots = Integer.highestOneBit(Math.max(1, this.submitted.size()) * 2 - 1) << 1;
        long bytes = (slots + 1L) * 16;
        if (bytes > this.buffer.size()) {
            this.buffer.free();
            this.buffer = new GlBuffer(bytes);
        }
        long header = UploadStream.INSTANCE.upload(this.buffer, 0, bytes);
        MemoryUtil.memSet(header, 0, bytes);
        int mask = slots - 1;
        MemoryUtil.memPutInt(header, mask);
        long ptr = header + 16;
        var sections = this.submitted.iterator();
        while (sections.hasNext()) {
            long pos = sections.nextLong();
            int x = SectionPos.x(pos);
            int y = SectionPos.y(pos);
            int z = SectionPos.z(pos);
            int slot = (x * 73856093 ^ y * 19349663 ^ z * 83492791) & mask;
            while (MemoryUtil.memGetInt(ptr + slot * 16L + 12) != 0) {
                slot = (slot + 1) & mask;
            }
            long entry = ptr + slot * 16L;
            MemoryUtil.memPutInt(entry, x);
            MemoryUtil.memPutInt(entry + 4, y);
            MemoryUtil.memPutInt(entry + 8, z);
            MemoryUtil.memPutInt(entry + 12, this.firstSubmission.get(pos));
        }
        UploadStream.INSTANCE.commit();
    }

    public void free() {
        this.buffer.free();
    }
}
