package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SecondarySectionCacheTest {
    private static final long ARRAY_BYTES = WorldSection.SECTION_VOLUME * 8L;
    private static ActiveSectionTracker tracker(SecondarySectionCache cache, int identity, AtomicInteger loads) {
        return new ActiveSectionTracker(2, section -> {
            loads.incrementAndGet(); section.setUniform(identity + section.x);
            if ((section.x & 1) == 0) section.materialize();
            return 0;
        }, cache, null);
    }
    private static WorldSection acquire(ActiveSectionTracker tracker, int x) { return tracker.acquire(0, x, 0, 0, false); }

    @Test void defaultBudgetClampsHeapAndEntries() {
        assertEquals(64L << 20, SecondarySectionCache.defaultByteLimit(512L << 20));
        assertEquals(128L << 20, SecondarySectionCache.defaultByteLimit(2L << 30));
        assertEquals(256L << 20, SecondarySectionCache.defaultByteLimit(16L << 30));
        assertEquals(1024, SecondarySectionCache.defaultEntryLimit(1L << 30));
        assertEquals(2048, SecondarySectionCache.defaultEntryLimit(4L << 30));
    }

    @Test void worldsWithIdenticalCoordinatesShareBytesAndPromotionRefreshesGlobalLru() {
        var cache = new SecondarySectionCache(20, 2 * ARRAY_BYTES);
        var loads = new AtomicInteger(); var a = tracker(cache, 100, loads); var b = tracker(cache, 200, loads);
        var a0 = acquire(a, 0); a0.release();
        var b0 = acquire(b, 0); b0.release();
        assertEquals(2 * ARRAY_BYTES, cache.stats().voxelBytes());
        assertSame(a0, acquire(a, 0)); a0.release(); // a0 is now newest, b0 is the eviction candidate.
        acquire(a, 2).release();
        assertNull(b.acquireIfCached(WorldEngine.getWorldSectionId(0, 0, 0, 0)));
        assertSame(a0, acquire(a, 0)); assertEquals(100, a0.get(0)); a0.release();
        var reloaded = acquire(b, 0); assertNotSame(b0, reloaded); assertEquals(200, reloaded.get(0)); reloaded.release();
        assertEquals(4, loads.get()); assertEquals(2 * ARRAY_BYTES, cache.stats().voxelBytes());
        a.clearSecondaryCache(); b.clearSecondaryCache(); assertEquals(0, cache.stats().voxelBytes());
    }

    @Test void uniformSectionsUseNoVoxelArrayBudgetButStillConsumeEntryBudget() {
        var cache = new SecondarySectionCache(2, 0); var tracker = tracker(cache, 100, new AtomicInteger());
        for (int x : new int[]{1, 3, 5}) acquire(tracker, x).release();
        assertEquals(2, cache.stats().entries()); assertEquals(0, cache.stats().voxelBytes());
        assertNull(tracker.acquireIfCached(WorldEngine.getWorldSectionId(0, 1, 0, 0)));
        acquire(tracker, 0).release(); // A dense section does not fit even in an otherwise empty cache.
        assertEquals(2, cache.stats().entries(), "oversized admission must not flush fitting uniform entries");
        assertEquals(0, cache.stats().voxelBytes());
        assertNull(tracker.acquireIfCached(WorldEngine.getWorldSectionId(0, 0, 0, 0)));
        tracker.clearSecondaryCache();
    }

    @Test void referencedDirtyAndSavingSectionsCannotBeEvicted() {
        var cache = new SecondarySectionCache(1, ARRAY_BYTES); var tracker = tracker(cache, 10, new AtomicInteger());
        var live = acquire(tracker, 0);
        for (int x = 2; x < 12; x += 2) acquire(tracker, x).release();
        assertFalse(live.isFreed()); assertEquals(10, live.get(0));
        live.markDirty(); live.release();
        assertFalse(live.isFreed()); assertEquals(1, tracker.getLoadedCacheCount());
        live.setNotDirty(); live.exchangeIsInSaveQueue(true); tracker.tryUnload(live, 0);
        assertFalse(live.isFreed()); assertEquals(1, tracker.getLoadedCacheCount());
        live.exchangeIsInSaveQueue(false); tracker.tryUnload(live, 0);
        assertEquals(0, tracker.getLoadedCacheCount()); assertEquals(ARRAY_BYTES, cache.stats().voxelBytes());
        tracker.clearSecondaryCache();
    }

    @Test void dontCacheAndWorldCleanupReleaseOnlyTheirOwnReservations() {
        var cache = new SecondarySectionCache(10, 10 * ARRAY_BYTES);
        var a = tracker(cache, 10, new AtomicInteger()); var b = tracker(cache, 20, new AtomicInteger());
        acquire(a, 0).release(WorldSection.RELEASE_HINT_DONT_CACHE);
        assertEquals(0, cache.stats().entries());
        acquire(a, 2).release(); acquire(b, 2).release();
        a.clearSecondaryCache(); assertEquals(1, cache.stats().entries());
        assertEquals(0, a.getSecondaryCacheSize()); assertEquals(1, b.getSecondaryCacheSize());
        var keys = new ArrayList<Long>(); b.iterateSecondaryPositions(keys::add);
        assertEquals(java.util.List.of(WorldEngine.getWorldSectionId(0, 2, 0, 0)), keys);
        b.clearSecondaryCache(); assertEquals(0, cache.stats().voxelBytes());
    }

    @Test void concurrentPromotionEvictionAndPoolReuseKeepOwnershipAndAccounting() throws Exception {
        var cache = new SecondarySectionCache(7, 3 * ARRAY_BYTES);
        var a = tracker(cache, 1000, new AtomicInteger()); var b = tracker(cache, 2000, new AtomicInteger());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 4; t++) {
                int thread = t;
                tasks.add(executor.submit(() -> {
                    var tracker = (thread & 1) == 0 ? a : b;
                    int base = (thread & 1) == 0 ? 1000 : 2000;
                    for (int i = 0; i < 2000; i++) {
                        int x = (i * 7 + thread) % 31;
                        var section = acquire(tracker, x);
                        try { assertEquals(base + x, section.get(0)); assertFalse(section.isFreed()); }
                        finally { section.release(); }
                        var stats = cache.stats();
                        assertTrue(stats.voxelBytes() >= 0 && stats.voxelBytes() <= stats.maxBytes());
                        assertTrue(stats.entries() <= stats.maxEntries());
                    }
                }));
            }
            for (var task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertEquals(0, a.getLoadedCacheCount()); assertEquals(0, b.getLoadedCacheCount());
        assertEquals(cache.stats().entries(), a.getSecondaryCacheSize() + b.getSecondaryCacheSize());
        a.clearSecondaryCache(); b.clearSecondaryCache(); assertEquals(0, cache.stats().voxelBytes());
    }
}
