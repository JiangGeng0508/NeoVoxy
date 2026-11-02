package me.cortex.voxy.client.distgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationBackpressureTest {
    private static final long MAX = 45_000_000;

    @Test void eachDownstreamPausesAboveThreeQuartersAndAllMustRecoverBelowHalf() {
        int[][] triggers = {{1501, 0, 0}, {0, 901, 0}, {0, 0, 1501}};
        for (int[] counts : triggers) {
            var gate = new GenerationBackpressure();
            assertFalse(gate.allow(counts[0], counts[1], counts[2], 0, 0, MAX));
            assertFalse(gate.allow(1000, 0, 0, 0, 0, MAX));
            assertFalse(gate.allow(0, 600, 0, 0, 0, MAX));
            assertFalse(gate.allow(0, 0, 1000, 0, 0, MAX));
            assertFalse(gate.allow(0, 0, 0, 0.5, 0, MAX));
            assertTrue(gate.allow(999, 599, 999, 0.49, 0, MAX));
            assertTrue(gate.allow(1500, 900, 1500, 0.75, 0, MAX));
            assertFalse(gate.allow(0, 0, 0, 0.751, 0, MAX));
        }
    }

    @Test void tickTimeKeepsConfiguredCeilingAndHasRecoveryMargin() {
        var gate = new GenerationBackpressure();
        assertTrue(gate.allow(0, 0, 0, 0, MAX - 1, MAX));
        assertFalse(gate.allow(0, 0, 0, 0, MAX, MAX));
        assertFalse(gate.allow(0, 0, 0, 0, 40_500_000, MAX));
        assertTrue(gate.allow(0, 0, 0, 0, 40_499_999, MAX));
        assertFalse(gate.allow(0, 0, 0, 1, 0, MAX));
        gate.reset();
        assertFalse(gate.paused());
        assertTrue(gate.allow(0, 0, 0, 0.7, MAX - 1, MAX));
    }

    @Test void retainedMeshBytesPauseGenerationEvenWithNoQueuedMeshKeys() {
        var gate = new GenerationBackpressure();
        assertFalse(gate.allow(0, 0, 0, 0, 0.751, 0, MAX));
        assertFalse(gate.allow(0, 0, 0, 0, 0.50, 0, MAX));
        assertFalse(gate.allow(0, 0, 0, 0.50, 0.49, 0, MAX));
        assertTrue(gate.allow(0, 0, 0, 0.49, 0.49, 0, MAX));
        assertTrue(gate.allow(0, 0, 0, 0, 0.75, 0, MAX));
    }

    @Test void ingestPayloadBytesPauseAndRequireRecoveryEvenWithFewTasks() {
        var gate = new GenerationBackpressure();
        assertFalse(gate.allow(1, 0, 0, 0, 0, 0.751, 0, MAX));
        assertFalse(gate.allow(0, 0, 0, 0, 0, 0.50, 0, MAX));
        assertFalse(gate.allow(0, 0, 0, 0, 0.50, 0.49, 0, MAX));
        assertTrue(gate.allow(0, 0, 0, 0, 0.49, 0.49, 0, MAX));
    }
}
