package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceQueueTest {
    @Test void continuousParentUpdatesCannotStarveLaterTiles() {
        var queue = new SurfaceQueue(4);
        long section = WorldEngine.getWorldSectionId(4, -1, 0, -2);
        var visited = new HashSet<SurfaceQueue.Tile>();
        for (int i = 0; i < 1024; i++) {
            queue.offer(section);
            assertTrue(visited.add(queue.poll()), "every tile must get a turn during continuous generation");
        }
        assertEquals(1024, visited.size());
        assertTrue(visited.contains(new SurfaceQueue.Tile(-1, -33)));
    }

    @Test void busyXaeroRegionDoesNotBlockOtherRegionsOrLoseItsRetry() {
        var queue = new SurfaceQueue(4);
        queue.offer(WorldEngine.getWorldSectionId(4, -1, 0, -1));
        var blocked = queue.poll(0);
        queue.retry(blocked.x(), blocked.z(), 0);
        // New voxel notifications must not bypass a pending source load's backoff.
        queue.offer(WorldEngine.getWorldSectionId(4, -1, 1, -1));
        queue.offer(WorldEngine.getWorldSectionId(0, 16, 0, 16));
        for (int i = 0; i < 4; i++) {
            var tile = queue.poll(1);
            assertNotNull(tile);
            assertTrue(tile.x() >= 32 && tile.z() >= 32);
        }
        assertNull(queue.poll(249_999_999));
        var retried = new HashSet<SurfaceQueue.Tile>();
        SurfaceQueue.Tile tile;
        while ((tile = queue.poll(250_000_000)) != null) assertTrue(retried.add(tile));
        assertEquals(1024, retried.size());
        assertTrue(retried.contains(blocked));
        assertEquals(0, queue.regionCount());
    }

    @Test void coalescesVerticalUpdatesAndExpandsCoarseNegativeCoordinatesExactlyOnce() {
        var queue = new SurfaceQueue(4);
        for (int y = -64; y < 64; y++) queue.offer(WorldEngine.getWorldSectionId(4, -1, y, -2));
        assertEquals(1, queue.regionCount());
        var found = new HashSet<SurfaceQueue.Tile>();
        SurfaceQueue.Tile tile;
        while ((tile = queue.poll()) != null) assertTrue(found.add(tile));
        assertEquals(1024, found.size());
        assertTrue(found.contains(new SurfaceQueue.Tile(-32, -64)));
        assertTrue(found.contains(new SurfaceQueue.Tile(-1, -33)));
        assertFalse(found.contains(new SurfaceQueue.Tile(0, -33)));
    }

    @Test void floodingRemainsBoundedAndClearIsolatesOldWorldWork() {
        var queue = new SurfaceQueue(16);
        for (int x = 0; x < 100000; x++) queue.offer(WorldEngine.getWorldSectionId(4, x, 0, 0));
        assertEquals(16, queue.regionCount());
        assertEquals(99984, queue.overflowCount());
        queue.clear();
        assertNull(queue.poll());
    }

    @Test void sourceAndPrecisionRulesDoNotDependOnSessionMemory() {
        for (int lod = 0; lod <= 4; lod++) for (boolean provisional : new boolean[]{false, true}) {
            int stored = SurfaceProvenance.encode(lod + 1, provisional);
            assertEquals(lod + 1, SurfaceProvenance.source(stored));
            assertEquals(provisional, SurfaceProvenance.provisional(stored));
            assertFalse(SurfaceProvenance.mayReplace(0, lod, provisional), "real map must remain protected");
            assertTrue(SurfaceProvenance.mayReplace(SurfaceProvenance.encode(6, true), lod, provisional));
            if (lod > 0) assertFalse(SurfaceProvenance.mayReplace(SurfaceProvenance.encode(lod, true), lod, provisional));
        }
        assertFalse(SurfaceProvenance.mayReplace(SurfaceProvenance.encode(1, false), 0, true));
    }
}
