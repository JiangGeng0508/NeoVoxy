package me.cortex.voxy.client.core.util;

import me.cortex.voxy.commonImpl.VoxyProfile;
import me.cortex.voxy.testing.GpuTestContext;
import me.cortex.voxy.testing.Reflect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Deque;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL33C.*;

@EnabledIfSystemProperty(named = "voxy.verifyScreenspaceGpu", matches = "true")
class GPUTimingGpuTest {
    @Test void realTimestampsSurviveF3DisableAndFreeAllQueryStates() throws Exception {
        try (var context = GpuTestContext.open("GPUTimingGpuTest")) {
            var timing = new GPUTiming();
            VoxyProfile.start();
            try {
                timing.setEnabled(false); // An active profile owns an independent request.
                timing.beginViewport(17, true);
                timing.marker("draw");
                timing.marker();
                timing.endViewport();
                timing.beginViewport(17, false);
                timing.marker();
                timing.endViewport();
                // The test waits to make availability deterministic; production poll never waits.
                glFinish();
                timing.poll();
                assertTrue(VoxyProfile.report().contains("GPU main: 1 valid viewport samples from 1 game frames"));
                assertTrue(VoxyProfile.report().contains("GPU secondary: 1 valid viewport samples from 1 game frames"));
                timing.poll();
                assertTrue(VoxyProfile.report().contains("GPU main: 1 valid viewport samples"));
                var allQueries = new HashSet<Integer>(Reflect.<Deque<Integer>>get(timing, "pool"));
                assertEquals(5, allQueries.size());
                for (int query : allQueries) assertTrue(glIsQuery(query));
                timing.beginViewport(18, true);
                timing.marker();
                timing.endViewport(); // Queued, not polled.
                timing.beginViewport(18, false); // Active, not submitted.
                timing.free(); // Includes pool, queued and active query objects.
                for (int query : allQueries) assertFalse(glIsQuery(query));
                assertEquals(GL_NO_ERROR, glGetError());
            } finally {
                VoxyProfile.stop();
                timing.free();
            }
        }
    }
}
