package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** Cache-key compatibility cases adapted from WorldSectionPositionVerification. */
class WorldSectionPositionTest {
    @Test
    void preservesLegacyCacheKeysIncludingSignedLimits() {
        assertKey(0x0000000000000000L, 0, 0, 0, 0);
        assertKey(0x413FFDD32FF97DB0L, 4, -26_661, 19, -8_910);
        assertKey(0x080FBA9EEF2FF350L, 0, -852_171, -128, -284_178);
        assertKey(0x07FE363C81C9C380L, 0, 1_875_000, 127, -1_875_000);
        assertKey(0xF808000008000000L, 15, -(1 << 23), -128, -(1 << 23));
        assertKey(0xF7F7FFFFF7FFFFF0L, 15, (1 << 23) - 1, 127, (1 << 23) - 1);
    }

    @Test
    void preservesExtendedKeysAndSeparatesThemFromLegacyHeights() {
        assertKey(0x0F7FFBA9EEE5FE6BL, 0, -852_171, -129, -284_178);
        assertKey(0x0080FDD320F2FB61L, 0, -426_576, 128, -142_560);
        assertKey(0x0130FDD320F2FB61L, 0, -426_576, 304, -142_560);
        assertKey(0x028CFBA9EEE5FE6BL, 0, -852_171, 652, -284_178);
        assertKey(0x02FEFBA9EEE5FE6BL, 0, -852_171, 766, -284_178);
        assertKey(0xF800800000800001L, 15, -(1 << 22), -(1 << 11), -(1 << 23));
        assertKey(0xF7FF7FFFFF7FFFFFL, 15, (1 << 22) - 1, (1 << 11) - 1, (1 << 23) - 1);
        for (int y : new int[]{-129, 128, 304, 652, 766}) {
            assertNotEquals(WorldEngine.getWorldSectionId(0, -852_171, y, -284_178),
                    WorldEngine.getWorldSectionId(0, -852_171, (byte) y, -284_178),
                    "extended heights must not alias their truncated legacy height");
        }
    }

    @Test
    void allHeightsAndLevelsRoundTripWithBoundaryAndRandomHorizontalCoordinates() {
        var random = new SplittableRandom(0x564F5859L);
        // All 12-bit heights and 4-bit format levels, including the two layout transitions.
        for (int y = -(1 << 11); y < (1 << 11); y++) {
            int xLimit = 1 << (y >= -128 && y <= 127 ? 23 : 22);
            for (int level = 0; level < 16; level++) {
                int corner = (y + level) & 3;
                int x = (corner & 1) == 0 ? -xLimit : xLimit - 1;
                int z = (corner & 2) == 0 ? -(1 << 23) : (1 << 23) - 1;
                assertRoundTrip(level, x, y, z);
                assertRoundTrip(level, random.nextInt(-xLimit, xLimit), y,
                        random.nextInt(-(1 << 23), 1 << 23));
            }
        }
    }

    @Test
    void hierarchyCrossesExtendedAndLegacyLayoutsWithoutLosingNegativeCoordinates() {
        for (int baseY : new int[]{-652, -129, 128, 652, 766}) {
            int baseX = -852_171, baseZ = -284_178;
            for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
                int scale = 1 << level;
                int x = Math.floorDiv(baseX, scale);
                int y = Math.floorDiv(baseY, scale);
                int z = Math.floorDiv(baseZ, scale);
                long parent = assertRoundTrip(level, x, y, z);
                long child = assertRoundTrip(0, WorldEngine.getX(parent) * scale,
                        WorldEngine.getY(parent) * scale, WorldEngine.getZ(parent) * scale);
                assertTrue(baseX >= WorldEngine.getX(child) && baseX < WorldEngine.getX(child) + scale);
                assertTrue(baseY >= WorldEngine.getY(child) && baseY < WorldEngine.getY(child) + scale);
                assertTrue(baseZ >= WorldEngine.getZ(child) && baseZ < WorldEngine.getZ(child) + scale);
            }
        }
    }

    @Test
    void rejectsCoordinatesOutsideExtendedLayoutCapacity() {
        for (int y : new int[]{-129, 128}) {
            assertOutOfRange(-(1 << 22) - 1, y, 0);
            assertOutOfRange(1 << 22, y, 0);
            assertOutOfRange(0, y, -(1 << 23) - 1);
            assertOutOfRange(0, y, 1 << 23);
        }
        for (int y : new int[]{Integer.MIN_VALUE, -(1 << 11) - 1, 1 << 11, Integer.MAX_VALUE}) {
            assertOutOfRange(0, y, 0);
        }
    }

    private static void assertKey(long expected, int level, int x, int y, int z) {
        // Fixed wire-format values catch layout changes that an encoder/decoder round trip can hide.
        assertEquals(expected, assertRoundTrip(level, x, y, z));
        assertEquals(level, WorldEngine.getLevel(expected));
        assertEquals(x, WorldEngine.getX(expected));
        assertEquals(y, WorldEngine.getY(expected));
        assertEquals(z, WorldEngine.getZ(expected));
    }

    private static long assertRoundTrip(int level, int x, int y, int z) {
        long key = WorldEngine.getWorldSectionId(level, x, y, z);
        assertEquals(level, WorldEngine.getLevel(key), "level");
        assertEquals(x, WorldEngine.getX(key), "x");
        assertEquals(y, WorldEngine.getY(key), "y");
        assertEquals(z, WorldEngine.getZ(key), "z");
        if (y >= -128 && y <= 127) assertEquals(0L, key & 15L, "legacy reserved bits");
        else assertEquals(1L, key & 1L, "extended format marker");
        return key;
    }

    private static void assertOutOfRange(int x, int y, int z) {
        assertThrows(IllegalArgumentException.class, () -> WorldEngine.getWorldSectionId(0, x, y, z));
    }
}
