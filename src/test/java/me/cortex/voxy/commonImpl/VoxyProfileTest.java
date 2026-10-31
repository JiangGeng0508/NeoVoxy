package me.cortex.voxy.commonImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoxyProfileTest {
    @AfterEach void stop() { VoxyProfile.stop(); }

    @Test void onlyCompletedQueriesFromCurrentSessionCount() {
        VoxyProfile.start();
        long old = VoxyProfile.gpuCaptureId();
        VoxyProfile.stop();
        VoxyProfile.start();
        VoxyProfile.recordGpuSample(old, 1, true, new String[]{"draw"}, new long[]{99_000_000});
        assertTrue(VoxyProfile.report().contains("no GPU samples"));
        long current = VoxyProfile.gpuCaptureId();
        VoxyProfile.recordGpuSample(current, 2, true, new String[]{"draw"}, new long[]{1_000_000});
        VoxyProfile.recordGpuSample(current, 2, false, new String[]{"draw"}, new long[]{2_000_000});
        VoxyProfile.recordGpuSample(current, 2, false, new String[]{"draw"}, new long[]{4_000_000});
        String report = VoxyProfile.report();
        assertTrue(report.contains("GPU main: 1 valid viewport samples from 1 game frames"));
        assertTrue(report.contains("GPU secondary: 2 valid viewport samples from 1 game frames"));
        VoxyProfile.stop();
        VoxyProfile.recordGpuSample(current, 3, true, new String[]{"draw"}, new long[]{99_000_000});
        assertTrue(VoxyProfile.report().contains("GPU main: 1 valid viewport samples"));
    }
}
