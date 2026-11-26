package me.cortex.voxy.common.world;

import java.util.LinkedHashMap;

/** Shared by the renderer and server engines. Only released, clean sections enter this cache. */
final class IdleSectionCache {
    private record Key(ActiveSectionTracker tracker, long section) {}

    private final LinkedHashMap<Key, WorldSection> sections = new LinkedHashMap<>();
    private final int capacity;

    IdleSectionCache(int capacity) {
        this.capacity = capacity;
    }

    synchronized WorldSection take(ActiveSectionTracker tracker, long key) {
        var section = this.sections.remove(new Key(tracker, key));
        if (section != null) tracker.idleSections--;
        return section;
    }

    synchronized void add(WorldSection section) {
        var tracker = section.tracker;
        if (tracker.engine != null && !tracker.engine.isLive()) {
            section._releaseArray();
            return;
        }
        var key = new Key(tracker, section.key);
        if (this.sections.putIfAbsent(key, section) != null) {
            throw new IllegalStateException("Duplicate idle section " + section.key);
        }
        tracker.idleSections++;
        if (this.sections.size() > this.capacity) {
            var entries = this.sections.values().iterator();
            var oldest = entries.next();
            entries.remove();
            oldest.tracker.idleSections--;
            oldest._releaseArray();
        }
    }

    synchronized void removeAll(ActiveSectionTracker tracker) {
        if (tracker.idleSections == 0) return;
        var entries = this.sections.values().iterator();
        while (entries.hasNext()) {
            var section = entries.next();
            if (section.tracker == tracker) {
                entries.remove();
                tracker.idleSections--;
                section._releaseArray();
            }
        }
    }

    synchronized int size(ActiveSectionTracker tracker) {
        return tracker.idleSections;
    }

    synchronized int size() {
        return this.sections.size();
    }

    int capacity() {
        return this.capacity;
    }
}
