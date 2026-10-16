package me.cortex.voxy.client.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SectionHandoffTest {
    @Test
    void aSectionCenterInsideTheBoundaryDoesNotHideItsOuterCorners() {
        assertTrue(SectionHandoff.insideBoundary(0, 0, 8, 128 * 128));
        for (int signX : new int[]{-1, 1}) {
            for (int signZ : new int[]{-1, 1}) {
                assertFalse(SectionHandoff.insideBoundary(signX * 120, signZ * 8, 8, 128 * 128));
                assertFalse(SectionHandoff.insideBoundary(signX * 88, signZ * 88, 8, 128 * 128));
                assertTrue(SectionHandoff.insideBoundary(signX * 72, signZ * 72, 8, 128 * 128));
            }
        }
    }

    @Test
    void copycatAndDomumOverhangsStayVisibleLongerThanLittleTiles() {
        assertTrue(SectionHandoff.insideBoundary(52, 0, 8, 64 * 64));
        assertFalse(SectionHandoff.insideBoundary(52, 0, 12, 64 * 64));
        assertTrue(SectionHandoff.insideBoundary(40, 0, 12, 64 * 64));
    }

    @Test
    void theExactBoundaryBelongsToLod() {
        // Farthest corner is (20, 8), exactly on the squared radius 464.
        assertFalse(SectionHandoff.insideBoundary(12, 0, 8, 464));
        assertTrue(SectionHandoff.insideBoundary(12, 0, 8, 465));
        assertFalse(SectionHandoff.insideBoundary(0, 0, 8, 0));
    }

    @Test
    void frameIdAndTimestampAreSharedUntilTheNextFrameBegins() {
        SectionHandoff.beginFrame();
        long first = SectionHandoff.frameId();
        long timestamp = SectionHandoff.frameTimeNanos();
        assertEquals(first, SectionHandoff.frameId());
        assertEquals(timestamp, SectionHandoff.frameTimeNanos());
        SectionHandoff.beginFrame();
        assertEquals(first + 1, SectionHandoff.frameId());
        assertTrue(SectionHandoff.frameTimeNanos() - timestamp >= 0);
    }
}
