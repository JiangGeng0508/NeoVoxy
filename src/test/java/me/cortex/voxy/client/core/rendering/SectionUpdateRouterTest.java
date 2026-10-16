package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SectionUpdateRouterTest {
    @Test
    void cornerEditsInvalidateAllDependentConnectedFaces() {
        var router = new SectionUpdateRouter();
        Set<Long> updates = new HashSet<>();
        router.setCallbacks(ignored -> {}, updates::add, ignored -> {});
        var center = WorldSection._createRawUntrackedUnsafeSection(2, -5, 1, 7);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    router.watch(2, -5 + x, 1 + y, 7 + z, WorldEngine.UPDATE_TYPE_BLOCK_BIT);
                }
            }
        }
        int cornerMask = 1 | 8 | 32; // -Y, +X, +Z
        router.triggerNeighborRemesh(center, cornerMask, true);
        assertEquals(7, updates.size());
        assertTrue(updates.contains(WorldEngine.getWorldSectionId(2, -4, 0, 8)));
        assertFalse(updates.contains(center.key));
        assertFalse(updates.contains(WorldEngine.getWorldSectionId(2, -6, 0, 8)));

        updates.clear();
        router.triggerNeighborRemesh(center, cornerMask, false);
        assertEquals(Set.of(
                WorldEngine.getWorldSectionId(2, -5, 0, 7),
                WorldEngine.getWorldSectionId(2, -4, 1, 7),
                WorldEngine.getWorldSectionId(2, -5, 1, 8)), updates);
        updates.clear();
        router.triggerNeighborRemesh(center, 63, true);
        assertEquals(26, updates.size());
        updates.clear();
        router.triggerNeighborRemesh(center, 0, true);
        assertTrue(updates.isEmpty());
    }
}
