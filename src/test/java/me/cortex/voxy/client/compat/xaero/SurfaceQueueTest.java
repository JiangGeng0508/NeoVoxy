package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceQueueTest {
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
