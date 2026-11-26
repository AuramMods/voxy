package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.section.LodSectionStorage;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class IdleSectionCacheTest {
    @Test
    void sharesCapacityWithoutSharingSectionsAcrossEngines() {
        var cache = new IdleSectionCache(2);
        var first = new ActiveSectionTracker(1, section -> 0, null, cache);
        var second = new ActiveSectionTracker(1, section -> 0, null, cache);
        long key = WorldEngine.getWorldSectionId(0, 1, 0, 0);
        long otherKey = WorldEngine.getWorldSectionId(0, 2, 0, 0);
        var oldest = first.acquire(key, false);
        oldest.release();
        var otherDimension = second.acquire(key, false);
        otherDimension.release();
        assertNotSame(oldest, otherDimension);

        var live = first.acquire(otherKey, false);
        assertSame(live, first.acquire(otherKey, false));
        live.release();
        assertEquals(2, cache.size());
        live.release();
        assertEquals(2, cache.size());
        assertNull(oldest._unsafeGetRawDataArray());
        assertEquals(1, first.cacheStats().activeHits());
        assertEquals(2, first.cacheStats().storageLoads());

        assertSame(otherDimension, second.acquire(key, false));
        assertEquals(1, second.cacheStats().idleHits());
        otherDimension.release();
        first.clearIdleSections();
        assertEquals(0, first.cacheStats().idle());
        assertEquals(1, second.cacheStats().idle());
        second.clearIdleSections();
        assertEquals(0, cache.size());
    }

    @Test
    void trimsColdEngineAfterLastReferenceAndReclaimsIdleSectionsOnClose(@TempDir Path temp) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var engine = new WorldEngine(new LodSectionStorage(temp.resolve("idle.sqlite")));
        long key = WorldEngine.getWorldSectionId(0, 1, 0, 0);
        var initial = engine.acquire(key);
        initial.release();
        engine.acquireRef();
        engine.lastActiveTime = 0;
        engine.trimIdleSectionCache();
        assertEquals(1, engine.sectionCacheStats().idle());
        engine.releaseRef();
        engine.lastActiveTime = 0;
        engine.trimIdleSectionCache();
        assertEquals(0, engine.sectionCacheStats().idle());
        assertNull(initial._unsafeGetRawDataArray());

        var reloaded = engine.acquire(key);
        reloaded.release();
        assertEquals(1, engine.sectionCacheStats().idle());
        engine.free();
        assertNull(reloaded._unsafeGetRawDataArray());
        assertEquals(0, engine.sectionCacheStats().idle());
    }
}
