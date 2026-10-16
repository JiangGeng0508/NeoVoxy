package me.cortex.voxy.client.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Time-boundary cases adapted from neo-voxy-multiversion's LiveHandoffVerification. */
class LiveHandoffStateTest {
    @Test
    void allRenderEntrypointsKeepTheFirstDecisionUntilTheNextFrame() {
        var state = new LiveHandoffState();
        assertFalse(state.update(1, 0, false));
        assertFalse(state.update(1, 500_000_000L, true));
        assertFalse(state.update(2, 10_000_000L, true));
        assertFalse(state.update(3, 259_999_999L, true));
        assertFalse(state.update(3, 260_000_000L, true), "crossing 250ms mid-frame must not change ownership");
        assertTrue(state.update(4, 260_000_000L, true));
        assertTrue(state.update(4, 270_000_000L, false), "other render passes must reuse the same decision");
        assertFalse(state.update(5, 280_000_000L, false));
        assertTrue(state.reacquiring());
        assertTrue(state.evaluated(5));
        assertFalse(state.evaluated(6));
    }

    @Test
    void interruptedReadinessRestartsTheEntireWait() {
        var state = new LiveHandoffState();
        assertFalse(state.update(1, 0, false));
        assertFalse(state.update(2, 300_000_000L, true));
        assertFalse(state.update(3, 400_000_000L, false));
        assertFalse(state.update(4, 550_000_000L, true));
        assertFalse(state.update(5, 799_999_999L, true));
        assertTrue(state.update(6, 800_000_000L, true));
        assertFalse(state.reacquiring());
    }

    @Test
    void initiallyReadyModelsStayLiveAndZeroOrNegativeClockValuesAreValid() {
        var state = new LiveHandoffState();
        assertFalse(state.reacquiring());
        assertTrue(state.update(1, -1_000_000_000L, true));
        assertFalse(state.update(2, -900_000_000L, false));
        assertFalse(state.update(3, 0, true));
        assertFalse(state.update(4, 249_999_999L, true));
        assertTrue(state.update(5, 250_000_000L, true));
    }

    @Test
    void oneChunkInsetPreventsRepeatedHandoffsNearTheTrackingBoundary() {
        var state = new LiveHandoffState();
        assertEquals(224.0, state.handoverRange(224));
        assertTrue(atDistance(state, 1, 0, 215));
        assertFalse(atDistance(state, 2, 10_000_000L, 225));
        assertEquals(208.0, state.handoverRange(224));
        assertFalse(atDistance(state, 3, 500_000_000L, 215), "returning just inside the outer boundary is insufficient");
        assertFalse(atDistance(state, 4, 600_000_000L, 208));
        assertFalse(atDistance(state, 5, 700_000_000L, 209), "leaving the inset interrupts reacquisition");
        assertFalse(atDistance(state, 6, 800_000_000L, 208));
        assertTrue(atDistance(state, 7, 1_050_000_000L, 208));
        assertEquals(224.0, state.handoverRange(224));
        assertTrue(atDistance(state, 8, 1_100_000_000L, 215), "live ownership retains the outer range");
        assertFalse(atDistance(state, 9, 1_200_000_000L, 225));
        assertEquals(48.0, state.handoverRange(64));
        assertEquals(16.0, state.handoverRange(16), "small view distances retain at least one chunk");
    }

    private static boolean atDistance(LiveHandoffState state, long frame, long time, double distance) {
        return state.update(frame, time, distance <= state.handoverRange(224));
    }
}
