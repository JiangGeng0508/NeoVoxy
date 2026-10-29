package me.cortex.voxy.common.world;

import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DirectSectionWriteTest {
    private static final long STONE = Mapper.composeMappingId((byte) 15, 1, 0);

    @Test void directWriteReturnsPreviousValueAndPreservesUniformAndLeafCounts() {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        assertEquals(Mapper.AIR, section.set(3, 4, 5, Mapper.AIR));
        assertTrue(section.isUniform(), "equal writes must not allocate a dense array");
        assertEquals(Mapper.AIR, section.set(3, 4, 5, STONE));
        assertEquals(STONE, section.get(WorldSection.getIndex(3, 4, 5)));
        assertEquals(1, section.getNonEmptyBlockCount());
        assertEquals(255, Byte.toUnsignedInt(section.getNonEmptyChildren()));
        assertEquals(STONE, section.set(3, 4, 5, STONE));
        assertEquals(1, section.getNonEmptyBlockCount());
        assertEquals(STONE, section.set(3, 4, 5, Mapper.AIR));
        assertEquals(0, section.getNonEmptyBlockCount());
        assertEquals(0, section.getNonEmptyChildren());
    }

    @Test void directAirOnlyProvesFullySuppliedOctantsAndSurvivesSaveLoadAtEveryLevel() {
        for (int level : new int[]{0, 1, 4, 5, 15}) {
            var section = WorldSection._createRawUntrackedUnsafeSection(level, -1, 0, -1);
            for (int octant = 0; octant < 8; octant++) {
                int bx = (octant & 1) * 16, bz = ((octant >> 1) & 1) * 16, by = ((octant >> 2) & 1) * 16;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    section.set(bx + x, by + y, bz + z, Mapper.AIR);
                    section.set(bx + x, by + y, bz + z, Mapper.AIR);
                    assertEquals(x == 15 && y == 15 && z == 15, section.isVoxelKnown(bx, by, bz));
                }
            }
            assertEquals(255, section.getKnownOctants());
            assertTrue(section.isUniform());
            var restored = WorldSection._createRawUntrackedUnsafeSection(level, -1, 0, -1);
            assertTrue(SaveLoadSystem3.deserialize(restored, SaveLoadSystem3.serialize(section)));
            assertEquals(255, restored.getKnownOctants());
        }
    }

    @Test void coarseTerrainDoesNotInventMissingChildren() {
        var section = WorldSection._createRawUntrackedUnsafeSection(4, 0, 0, 0);
        section.set(0, 0, 0, STONE);
        assertEquals(0, section.getNonEmptyChildren(), "SeedGen declares only children it actually supplies");
        assertEquals(0, section.getKnownOctants(), "one solid voxel cannot prove the remaining air");
    }
}
