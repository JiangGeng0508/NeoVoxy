package me.cortex.voxy.common.thread;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskTimingTest {
    @AfterEach void stop() { TaskTiming.stopCapture(); TaskTiming.stopProfile(); }
    @Test void captureAndProfileOwnIndependentWindows() {
        var capture = TaskTiming.startCapture(); var profile = TaskTiming.startProfile();
        long start = System.nanoTime();
        TaskTiming.record("queue/test", start, start + 1_000_123);
        assertTrue(capture.report().contains("p95=1.000"));
        TaskTiming.stopCapture();
        TaskTiming.record("queue/test", start, start + 2_000_000);
        assertTrue(capture.report().contains("count=1"));
        assertTrue(profile.report().contains("count=2"));
    }
    @Test void pendingOldWindowSamplesCannotLeakIntoANewSession() {
        var old = TaskTiming.startCapture();
        long start = System.nanoTime();
        var current = TaskTiming.startCapture();
        TaskTiming.record("queue/old", start, System.nanoTime());
        assertTrue(current.report().contains("no completed worker samples"));
        assertTrue(old.report().contains("no completed worker samples"));
    }
    @Test void boundedPercentilesRetainRecentSamplesButTotalsCoverTheWindow() {
        var capture = TaskTiming.startCapture();
        long start = System.nanoTime();
        TaskTiming.record("work/test", start, start + 1_000_000_000);
        for (int i = 0; i < 8192; i++) TaskTiming.record("work/test", start, start + 2_000_000);
        String report = capture.report();
        assertTrue(report.contains("count=8193 retained=8192 p50=2.000 p95=2.000 p99=2.000"));
        assertTrue(report.contains("max=1000.000"));
    }
}
