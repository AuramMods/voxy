package me.cortex.voxy.server;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

/** Called by the dimension's storage worker with exclusive ownership of its WorldEngine. */
final class ServerColumnWriter {
    private ServerColumnWriter() {}

    static void commit(WorldEngine world, LodSectionStorage storage, ServerChunkSnapshot chunk) {
        var changed = new Long2ObjectOpenHashMap<WorldSection>();
        world.setDirtyCallback((section, flags, neighbors) -> {
            if (changed.putIfAbsent(section.key, section) == null) section.acquire();
        });
        boolean committed = false;
        try {
            chunk.insertInto(world);
            var snapshots = changed.values().stream().map(LodSectionSnapshot::capture).toList();
            storage.commitColumn(chunk.position().toLong(), snapshots);
            committed = true;
        } finally {
            world.setDirtyCallback(null);
            for (var section : changed.values()) {
                section.setNotDirty();
                // A failed transaction must reload the last persisted value on retry.
                section.release(committed ? 0 : WorldSection.RELEASE_HINT_DONT_CACHE);
            }
        }
    }
}
