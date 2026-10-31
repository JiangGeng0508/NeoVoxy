package me.cortex.voxy.client;

import me.cortex.voxy.testing.Reflect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameCaptureTest {
    private static long[] stages(long nanos) {
        long[] stages = new long[FrameCapture.STAGES.length];
        stages[0] = nanos;
        return stages;
    }

    @Test void viewportVisitsNeverAdvanceFramesAndKeepRawStageSums() {
        var capture = new FrameCapture();
        capture.begin(10, 1_000_000);
        capture.viewport(false, stages(10));
        capture.viewport(true, stages(40));
        capture.viewport(false, stages(20));
        var first = capture.end(9_000_000);
        assertEquals(1, capture.size());
        assertEquals(-1, first.interval);
        assertEquals(8_000_000, first.render);
        assertArrayEquals(new int[]{1, 2}, first.viewports);
        assertEquals(40, first.stages[0][0]);
        assertEquals(30, first.stages[1][0]);
        capture.begin(11, 17_000_000);
        var second = capture.end(22_000_000); // A frame with no terrain still counts.
        assertEquals(2, capture.size());
        assertArrayEquals(new long[]{16_000_000}, capture.intervals());
        assertArrayEquals(new int[]{0, 0}, second.viewports);
        assertEquals(5_000_000, second.render);
    }

    @Test void partialAndDuplicateEventsDoNotProduceExtraFrames() {
        var capture = new FrameCapture();
        capture.viewport(true, stages(9));
        assertNull(capture.end(100));
        capture.begin(1, 100);
        capture.begin(1, 120);
        var frame = capture.end(200);
        assertEquals(100, frame.render);
        assertNull(capture.end(220));
        capture.begin(1, 240);
        assertNull(capture.end(250));
        assertEquals(1, capture.size());
    }

    @Test void delayedGpuResultsUseSubmittingFrameAndOnlyCompleteGroupsEnterPercentiles() {
        var capture = new FrameCapture();
        capture.begin(10, 100);
        capture.viewport(true, stages(40));
        capture.viewport(false, stages(20));
        capture.viewport(false, stages(20));
        var frame = capture.end(200);
        capture.begin(11, 300);
        capture.viewport(true, stages(50));
        var next = capture.end(400);
        capture.gpu(10, true, new String[]{"draw"}, new long[]{2_000_000});
        capture.gpu(10, false, new String[]{"draw"}, new long[]{3_000_000});
        assertArrayEquals(new int[]{1, 1}, frame.gpuViewports);
        assertArrayEquals(new int[]{0, 0}, next.gpuViewports);
        assertTrue(capture.report().contains("secondary GPU total: no valid samples"));
        assertTrue(capture.report().contains("main: 2 viewport visits; GPU valid viewports=1, complete frames=1, unavailable viewports=1"));
        capture.gpu(10, false, new String[]{"draw"}, new long[]{4_000_000});
        assertEquals(7_000_000L, frame.gpu.get(1).get("draw"));
        capture.gpu(10, false, new String[]{"draw"}, new long[]{99}); // Excess/unknown samples ignored.
        capture.gpu(99, true, new String[]{"draw"}, new long[]{99});
        assertEquals(7_000_000L, frame.gpu.get(1).get("draw"));
        assertTrue(capture.report().contains("secondary: 2 viewport visits; GPU valid viewports=2, complete frames=1, unavailable viewports=0"));
    }

    @Test void percentilesUseRawSamplesWithoutPeakSmoothing() throws Exception {
        TimingStatistics.resetSamplers();
        double oldRolling = TimingStatistics.all.getRolling();
        try {
            Reflect.set(TimingStatistics.all, "runtime", 100_000_123L);
            TimingStatistics.update();
            Reflect.set(TimingStatistics.all, "runtime", 1_000_123L);
            TimingStatistics.update();
            assertTrue(TimingStatistics.all.getRolling() > 90);
            assertEquals(1_000_123L, TimingStatistics.all.getNanos());
            assertEquals(1_000_123L, TimingStatistics.snapshotNanos()[0]);
            TimingStatistics.resetSamplers();
            assertEquals(0, TimingStatistics.all.getNanos());
            assertTrue(TimingStatistics.all.getRolling() > 90);
        } finally {
            TimingStatistics.resetSamplers();
            Reflect.set(TimingStatistics.all, "rolling", oldRolling);
        }
        long[] samples = new long[100];
        java.util.Arrays.fill(samples, 1_000_123);
        samples[99] = 100_000_123;
        assertEquals(1_000_123, FrameCapture.percentile(samples, 95));
        assertEquals(1_000_123, FrameCapture.percentile(samples, 99));
        assertEquals(100_000_123, FrameCapture.percentile(samples, 100));
    }

    @Test void dedicatedWorkersAreIncludedInStallCapture() {
        assertTrue(FrameProfiler.isInterestingThread(new Thread("Dedicated Voxy Worker #3")));
        assertTrue(FrameProfiler.isInterestingThread(new Thread("Async Node Manager")));
        assertFalse(FrameProfiler.isInterestingThread(new Thread("Unrelated worker")));
    }

    @Test void aPreviousCaptureCannotWriteIntoTheNewSession() throws Exception {
        var capture = new FrameCapture();
        capture.begin(10, 100);
        capture.viewport(true, stages(40));
        var frame = capture.end(200);
        var captureField = FrameProfiler.class.getDeclaredField("capture");
        var idField = FrameProfiler.class.getDeclaredField("captureId");
        var activeField = FrameProfiler.class.getDeclaredField("active");
        captureField.setAccessible(true);
        idField.setAccessible(true);
        activeField.setAccessible(true);
        try {
            captureField.set(null, capture);
            idField.setLong(null, 2);
            activeField.setBoolean(null, true);
            FrameProfiler.onGpuSample(1, 10, true, new String[]{"draw"}, new long[]{99});
            assertEquals(0, frame.gpuViewports[0]);
            FrameProfiler.onGpuSample(2, 10, true, new String[]{"draw"}, new long[]{42});
            assertEquals(42L, frame.gpu.getFirst().get("draw"));
        } finally {
            activeField.setBoolean(null, false);
            captureField.set(null, null);
        }
    }
}
