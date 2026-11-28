package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

/** World-space regions invalidated by applied node or section metadata writes. */
public final class ShadowCasterChanges {
    public static final int MAX_REGIONS = 256;
    private static final long EMPTY = -1L;
    private final Int2LongOpenHashMap positions = new Int2LongOpenHashMap();
    private final Int2LongOpenHashMap nodeSelections = new Int2LongOpenHashMap();
    private final LongOpenHashSet regions = new LongOpenHashSet();
    private boolean fullRefresh;

    public ShadowCasterChanges() {
        this.positions.defaultReturnValue(EMPTY);
        this.nodeSelections.defaultReturnValue(EMPTY);
    }

    void apply(Int2IntOpenHashMap writes, long data, boolean rootsChanged) {
        this.fullRefresh |= rootsChanged;
        var iterator = writes.int2IntEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            int id = entry.getIntKey();
            boolean geometry = id < 0;
            // The second geometry word holds counts. The paired first word holds position.
            if (geometry && (id & 1) != 0) continue;
            long address = data + entry.getIntValue() * 16L;
            int high = MemoryUtil.memGetInt(address);
            int low = MemoryUtil.memGetInt(address + 4);
            int a = MemoryUtil.memGetInt(address + 8);
            int b = MemoryUtil.memGetInt(address + 12);
            boolean removed = high == -1 && low == -1;
            if (geometry) {
                int counts = writes.get(id + 1);
                long countsAddress = data + counts * 16L;
                removed = (high | low | a | b) == 0
                        && (counts < 0 || (MemoryUtil.memGetLong(countsAddress)
                        | MemoryUtil.memGetLong(countsAddress + 8)) == 0L);
            }
            long position = removed ? EMPTY : Integer.toUnsignedLong(low) | (long) high << 32;
            long oldPosition = removed ? this.positions.remove(id) : this.positions.put(id, position);
            if (!geometry) {
                // Child count, child pointer, and geometry affect selected meshes.
                // Request and cleanup flags alone leave shadow depth unchanged.
                long selection = Integer.toUnsignedLong(a & 0x00ffffff) << 32 | Integer.toUnsignedLong(b & 0x00ffffff);
                selection |= (long) (a & 0x1c000000) << 32;
                long oldSelection = removed ? this.nodeSelections.remove(id) : this.nodeSelections.put(id, selection);
                if (oldPosition == position && oldSelection == selection) continue;
            }
            if (this.fullRefresh) continue;
            if (oldPosition != EMPTY) this.regions.add(oldPosition);
            if (position != EMPTY) this.regions.add(position);
            this.fullRefresh = this.regions.size() > MAX_REGIONS;
            if (this.fullRefresh) this.regions.clear();
        }
    }

    public boolean requiresFullRefresh() {
        return this.fullRefresh;
    }

    public int regionCount() {
        return this.regions.size();
    }

    public void writeRegions(long address) {
        var iterator = this.regions.iterator();
        while (iterator.hasNext()) {
            long position = iterator.nextLong();
            MemoryUtil.memPutInt(address, WorldEngine.getX(position));
            MemoryUtil.memPutInt(address + 4, WorldEngine.getY(position));
            MemoryUtil.memPutInt(address + 8, WorldEngine.getZ(position));
            MemoryUtil.memPutInt(address + 12, WorldEngine.getLevel(position));
            address += 16;
        }
    }

    public void clear() {
        this.regions.clear();
        this.fullRefresh = false;
    }
}
