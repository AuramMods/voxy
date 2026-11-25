package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.Util;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/** Embeddium section coverage for the opaque and translucent terrain handoffs. */
public final class NearTerrainCoverage {
    private static final int FADE_MS = 350;
    private static final int TIME_MASK = Integer.MAX_VALUE;
    private static final int DEPARTING = Integer.MIN_VALUE;

    private final LongOpenHashSet submitted = new LongOpenHashSet();
    private LongOpenHashSet submittedTranslucent = new LongOpenHashSet();
    private LongOpenHashSet translucentSections = new LongOpenHashSet();
    private final Long2IntOpenHashMap transitions = new Long2IntOpenHashMap();
    private GlBuffer buffer = new GlBuffer(48);
    private boolean dirty = true;
    private long visibilityTime;
    private long nextVisibilityRefresh = Long.MAX_VALUE;

    public void beginSolidPass() {
        this.submitted.clear();
        this.submittedTranslucent.clear();
    }

    public void submit(int x, int y, int z) {
        this.submitted.add(sectionKey(x, y, z));
    }

    public void submitTranslucent(int x, int y, int z) {
        this.submittedTranslucent.add(sectionKey(x, y, z));
    }

    public void beginVisibilityPass() {
        this.visibilityTime = Util.getMillis();
        this.nextVisibilityRefresh = Long.MAX_VALUE;
    }

    public boolean visibilityExpired() {
        return Util.getMillis() >= this.nextVisibilityRefresh;
    }

    public boolean wasSubmitted(int x, int y, int z) {
        return this.transitions.containsKey(sectionKey(x, y, z));
    }

    public boolean updateVisibility(int x, int y, int z, boolean inRange) {
        long key = sectionKey(x, y, z);
        int state = this.transitions.get(key);
        if (state == 0) return inRange;

        int now = clock(this.visibilityTime);
        boolean wasDeparting = state < 0;
        int elapsed = (now - state) & TIME_MASK;
        if (wasDeparting == inRange) {
            // Reverse from the current weight when the camera doubles back mid-fade.
            elapsed = FADE_MS - Math.min(elapsed, FADE_MS);
            int start = (now - elapsed) & TIME_MASK;
            this.transitions.put(key, inRange ? start : start | DEPARTING);
            this.dirty = true;
        }
        if (inRange) return true;

        int remaining = FADE_MS - elapsed;
        if (remaining <= 0) return false;
        this.nextVisibilityRefresh = Math.min(this.nextVisibilityRefresh, this.visibilityTime + remaining);
        return true;
    }

    private static int clock(long time) {
        // Odd timestamps reserve zero for unused slots. Elapsed time wraps modulo 2^31.
        return (int) time & TIME_MASK | 1;
    }

    private static long sectionKey(int x, int y, int z) {
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x + 512) >> 10;
            x -= sector << 10;
            y += 16 + (256 - 32 - sector * 30);
        }
        return SectionPos.asLong(x, y, z);
    }

    public void upload(Viewport<?> viewport, boolean enabled) {
        if (!enabled) {
            this.submitted.clear();
            this.submittedTranslucent.clear();
        }
        int now = clock(Util.getMillis());
        if (!this.transitions.keySet().equals(this.submitted)) {
            this.transitions.keySet().retainAll(this.submitted);
            var sections = this.submitted.iterator();
            while (sections.hasNext()) {
                this.transitions.putIfAbsent(sections.nextLong(), now);
            }
            this.dirty = true;
        }
        if (!this.translucentSections.equals(this.submittedTranslucent)) {
            var previous = this.translucentSections;
            this.translucentSections = this.submittedTranslucent;
            this.submittedTranslucent = previous;
            this.dirty = true;
        }
        if (this.dirty) {
            this.upload();
            this.dirty = false;
        }
        viewport.nearTerrainSections = this.buffer;
        viewport.nearTerrainTime = now;
        // Never let a pass reuse a collection from a frame where no near draw ran.
        this.submitted.clear();
        this.submittedTranslucent.clear();
    }

    private void upload() {
        int slots = tableSize(this.transitions.size());
        int translucentSlots = this.translucentSections.isEmpty() ? 0 : tableSize(this.translucentSections.size());
        long bytes = (slots + translucentSlots + 1L) * 16;
        if (bytes > this.buffer.size()) {
            this.buffer.free();
            this.buffer = new GlBuffer(bytes);
        }
        long header = UploadStream.INSTANCE.upload(this.buffer, 0, bytes);
        MemoryUtil.memSet(header, 0, bytes);
        MemoryUtil.memPutInt(header, slots - 1);
        MemoryUtil.memPutInt(header + 4, FADE_MS);
        MemoryUtil.memPutInt(header + 8, translucentSlots);
        long ptr = header + 16;
        var opaque = this.transitions.long2IntEntrySet().fastIterator();
        while (opaque.hasNext()) {
            var section = opaque.next();
            writeSection(ptr, slots - 1, section.getLongKey(), section.getIntValue());
        }
        long translucentPtr = ptr + slots * 16L;
        var translucent = this.translucentSections.iterator();
        while (translucent.hasNext()) {
            writeSection(translucentPtr, translucentSlots - 1, translucent.nextLong(), 1);
        }
        UploadStream.INSTANCE.commit();
    }

    private static int tableSize(int count) {
        return Integer.highestOneBit(Math.max(1, count) * 2 - 1) << 1;
    }

    private static void writeSection(long table, int mask, long pos, int state) {
        int x = SectionPos.x(pos);
        int y = SectionPos.y(pos);
        int z = SectionPos.z(pos);
        int slot = (x * 73856093 ^ y * 19349663 ^ z * 83492791) & mask;
        while (MemoryUtil.memGetInt(table + slot * 16L + 12) != 0) {
            slot = (slot + 1) & mask;
        }
        long entry = table + slot * 16L;
        MemoryUtil.memPutInt(entry, x);
        MemoryUtil.memPutInt(entry + 4, y);
        MemoryUtil.memPutInt(entry + 8, z);
        MemoryUtil.memPutInt(entry + 12, state);
    }

    public void free() {
        this.buffer.free();
    }
}
