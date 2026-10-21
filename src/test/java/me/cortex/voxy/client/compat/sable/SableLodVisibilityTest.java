package me.cortex.voxy.client.compat.sable;

import org.joml.Matrix4f;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SableLodVisibilityTest {
    private static Matrix4f projection(float fov) {
        return new Matrix4f().perspective((float) Math.toRadians(fov), 16f / 9, .1f, 1_000_000);
    }

    @Test void sameViewCachesButZoomResolutionQualityNearRangeAndCameraInvalidate() {
        var visibility = new SableLodVisibility();
        var key = new Object();
        var calls = new AtomicInteger();
        Consumer<Vector3d> pose = p -> { calls.incrementAndGet(); p.add(0, 0, -100_000); };
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 256, 96);
        assertTrue(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose));
        assertEquals(8, calls.get());
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 256, 96);
        assertTrue(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose));
        assertEquals(8, calls.get());
        visibility.begin(1, 0, 0, 0, projection(7), 1920, 1080, 256, 96);
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose));
        visibility.begin(1, 0, 0, 0, projection(70), 7680, 4320, 256, 96);
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose));
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 256, 200_000);
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose));
        visibility.begin(1, 0, 0, -99_000, projection(70), 1920, 1080, 256, 96);
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose));
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 1, 96);
        visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, pose);
        assertEquals(48, calls.get());
    }

    @Test void unprojectedAndShadowPhasesKeepGeometryAndNextFrameRefreshesPose() {
        var visibility = new SableLodVisibility();
        var key = new Object();
        Consumer<Vector3d> far = p -> p.add(0, 0, -100_000);
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 256, 96);
        assertTrue(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, far));
        visibility.begin(1, 0, 0, 0, null, 1920, 1080, 256, 96);
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, p -> fail("unprojected pose evaluation")));
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 256, 96);
        assertTrue(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, far));
        visibility.begin(2, 0, 0, 0, projection(70), 1920, 1080, 256, 96);
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, p -> p.add(0, 0, -50)));
        visibility.clear();
        assertFalse(visibility.isSubpixel(key, 0, 0, 0, 0, 0, 0, far));
    }

    @Test void paddedRotatedTallAndNearPlaneBoundsAreConservative() {
        var visibility = new SableLodVisibility();
        visibility.begin(1, 0, 0, 0, projection(70), 1920, 1080, 256, 96);
        // The extra 16 blocks of attachments keep a one-block plot visible at 10,000 blocks.
        assertFalse(visibility.isSubpixel(new Object(), 0, 0, 0, 0, 0, 0, p -> p.add(0, 0, -10_000)));
        var pose = new Matrix4d().translate(0, 0, -100_000).rotateZ(Math.PI / 2);
        assertFalse(visibility.isSubpixel(new Object(), 0, 0, 0, 1, 3000, 1, pose::transformPosition));
        assertFalse(visibility.isSubpixel(new Object(), 0, 0, 0, 0, 0, 0, p -> p.add(0, 0, -1)));
        assertFalse(visibility.isSubpixel(new Object(), 0, 0, 0, 0, 0, 0, p -> p.set(Double.NaN)));
        visibility.begin(2, 0, 0, 0, projection(70), 0, 0, 256, 96);
        assertFalse(visibility.isSubpixel(new Object(), 0, 0, 0, 0, 0, 0, p -> p.add(0, 0, -100_000)));
    }
}
