package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.world.WorldEngine;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Adapted from the reference NativeLodSelectionVerification, with cache revision checks. */
class NativeLodSelectionTest {
    @Test
    void horizontalNearDetailAndZoomOverrideCoarseSelectionWhileStillViewsReuseCache() {
        var selection = new NativeLodSelection();
        var projection = projection(70);
        selection.update(projection, projection, 1920, 1080, 0, 0, 0, 256, 256);
        assertEquals(0, selection.level(0, 10000, 0, 16, 10016, 16));
        int far = selection.level(0, 0, -2048, 16, 16, -2032);
        assertTrue(far > 0);
        int evaluations = selection.evaluations, revision = selection.revision();
        assertEquals(far, selection.level(0, 0, -2048, 16, 16, -2032));
        assertEquals(evaluations, selection.evaluations);
        assertTrue(selection.cacheHits > 0);
        selection.update(new Matrix4f(projection), projection, 1920, 1080, 0, 0, 0, 256, 256);
        assertEquals(evaluations, selection.evaluations);
        assertEquals(revision, selection.revision());

        var zoom = projection(1);
        selection.update(zoom, zoom, 1920, 1080, 0, 0, 0, 256, 256);
        assertNotEquals(revision, selection.revision());
        assertEquals(0, selection.evaluations);
        assertEquals(0, selection.level(0, 0, -2048, 16, 16, -2032));
        selection.update(projection, projection, 1920, 1080, 0, 0, 0, 256, 4096);
        assertEquals(0, selection.level(0, 0, -2048, 16, 16, -2032), "near-distance changes refine a stationary view");
    }

    @Test
    void qualityResolutionAndCameraChangesInvalidateDecisions() {
        var selection = new NativeLodSelection();
        var projection = projection(70);
        int previous = 0;
        for (float quality : new float[]{28, 64, 123, 256, 512, 768, 1024}) {
            int revision = selection.revision();
            selection.update(projection, projection, 1920, 1080, 0, 0, 0, quality, 256);
            assertNotEquals(revision, selection.revision());
            assertEquals(NativeLodSelection.visibilityPixels(quality), selection.visibilityPixels);
            assertEquals(256, selection.fullDetailDistance);
            assertEquals(65536, selection.fullDetailDistanceSquared);
            int level = selection.level(-16, -16, -1024, 16, 16, -1008);
            assertTrue(level >= previous, "lower quality must not refine geometry");
            previous = level;
        }
        selection.update(projection, projection, 1920, 1080, 0, 0, 0, 256, 256);
        previous = selection.level(0, 0, -2048, 16, 16, -2032);
        int revision = selection.revision();
        selection.update(projection, projection, 3840, 2160, 0, 0, 0, 256, 256);
        assertNotEquals(revision, selection.revision());
        assertTrue(selection.level(0, 0, -2048, 16, 16, -2032) <= previous);
        revision = selection.revision();
        selection.update(projection, projection, 3840, 2160, 0, 0, -2048, 256, 256);
        assertNotEquals(revision, selection.revision(), "MVP excludes the camera's world translation");
        assertEquals(0, selection.level(0, 0, -2048, 16, 16, -2032));
    }

    @Test
    void crossSectionMeshesUseFinestTouchedLevelIncludingNegativeAndWorldBorderCoordinates() {
        var selection = new NativeLodSelection();
        var projection = projection(70);
        for (double camera : new double[]{0, -.125, 30000000.25, -30000000.25}) {
            selection.update(projection, projection, 1920, 1080, camera, 100, camera, 256, 256);
            double x0 = Math.floor(camera / 32) * 32, z0 = Math.floor((camera - 4096) / 32) * 32;
            int combined = selection.level(x0, 96, z0, x0 + 64, 128, z0 + 64);
            int expected = WorldEngine.MAX_LOD_LAYER;
            for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
                expected = Math.min(expected, selection.level(x0 + x * 32, 96, z0 + z * 32,
                        x0 + (x + 1) * 32, 128, z0 + (z + 1) * 32));
            }
            assertEquals(expected, combined);
        }
    }

    @Test
    void translatedAndScaledModelsPreserveWorldVoxelSize() {
        var selection = new NativeLodSelection();
        var projection = projection(70);
        for (double camera : new double[]{0, -.125, 30000000.25, -30000000.25}) {
            selection.update(projection, projection, 1920, 1080, camera, 100, camera, 256, 256);
            var transform = new Matrix4f().translate(8, -4, -4090);
            int level = selection.level(camera + 8, 96, camera - 4090, camera + 24, 112, camera - 4074);
            assertEquals(level == 0 ? 0 : 1 << level,
                    selection.gridSize(transform, camera, 100, camera, 0, 0, 0, 16, 16, 16), 1e-4);
            transform.scale(2);
            level = selection.level(camera + 8, 96, camera - 4090, camera + 40, 128, camera - 4058);
            assertEquals(level == 0 ? 0 : (1 << level) / 2f,
                    selection.gridSize(transform, camera, 100, camera, 0, 0, 0, 16, 16, 16), 1e-4);
        }
    }

    @Test
    void invalidInputsAndTraversalBudgetFallBackToFullDetail() {
        var selection = new NativeLodSelection();
        var projection = projection(70);
        assertEquals(0, selection.level(0, 0, -1000, 16, 16, -984));
        selection.update(projection, projection, 0, 0, 0, 0, 0, 256, 256);
        assertEquals(0, selection.level(0, 0, -1000, 16, 16, -984));
        selection.update(projection, projection, 1920, 1080, 0, 0, 0, 256, 256);
        assertEquals(0, selection.level(-1e20, -1e20, -1e20, 1e20, 1e20, 1e20));
        assertEquals(0, selection.level(Double.NaN, 0, 0, 1, 1, 1));
        assertEquals(0, selection.level(2, 0, 0, 1, 1, 1));
        // Near-plane intersection must refine even with near-detail forcing disabled.
        selection.update(projection, projection, 1920, 1080, 0, 0, 0, 1024, 0);
        assertTrue(selection.shouldDescend(0, 0, -1, 1));
        for (float quality : new float[]{0, Float.NaN, Float.POSITIVE_INFINITY}) {
            selection.update(projection, projection, 1920, 1080, 0, 0, 0, quality, 256);
            assertEquals(0, selection.level(0, 0, -2048, 16, 16, -2032));
        }
    }

    @Test
    void anotherViewCannotChangeSettingsOrInvalidateTheMainViewCache() {
        var main = new NativeLodSelection();
        var projection = projection(70);
        main.update(projection, projection, 1920, 1080, 0, 0, 0, 1024, 64);
        int level = main.level(0, 0, -2048, 16, 16, -2032);
        int revision = main.revision(), evaluations = main.evaluations;
        var secondary = new NativeLodSelection();
        secondary.update(projection(7), projection(7), 256, 128, 32, 100, 128, 28, 128);
        secondary.level(0, 0, -2048, 16, 16, -2032);
        assertEquals(1, main.visibilityPixels);
        assertEquals(64, main.fullDetailDistance);
        assertTrue(secondary.visibilityPixels < main.visibilityPixels);
        assertEquals(128, secondary.fullDetailDistance);
        assertEquals(revision, main.revision());
        assertEquals(level, main.level(0, 0, -2048, 16, 16, -2032));
        assertEquals(evaluations, main.evaluations);
    }

    private static Matrix4f projection(float fov) {
        return new Matrix4f().perspective((float) Math.toRadians(fov), 16f / 9, .1f, 50000);
    }
}
