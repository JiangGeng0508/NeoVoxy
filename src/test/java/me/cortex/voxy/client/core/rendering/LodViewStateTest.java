package me.cortex.voxy.client.core.rendering;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodViewStateTest {
    @Test
    void reuseRequiresAllDetailInputsAndCopiesTheMatrix() {
        var view = new LodViewState();
        var matrix = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .1f, 50000);
        assertFalse(view.matches(matrix, 1920, 1080, 256, 64, 0));
        view.set(matrix, 1920, 1080, 256, 64);
        assertTrue(view.matches(new Matrix4f(matrix), 1920, 1080, 256, 64, 0));
        assertFalse(view.matches(matrix, 3840, 2160, 256, 64, 0));
        assertFalse(view.matches(matrix, 1920, 1080, 28, 64, 0));
        assertFalse(view.matches(matrix, 1920, 1080, 256, 128, 0));
        matrix.scale(2, 2, 1);
        assertFalse(view.matches(matrix, 1920, 1080, 256, 64, 0));
        view.set(matrix, 1920, 1080, 256, 64);
        view.invalidate();
        assertFalse(view.matches(matrix, 1920, 1080, 256, 64, 0));
    }

    @Test
    void holdToleranceNeverAccumulatesAcrossReusedFramesOrMasksConfigChanges() {
        var view = new LodViewState();
        var matrix = new Matrix4f();
        view.set(matrix, 1920, 1080, 256, 64);
        assertTrue(view.matches(matrix.m30(.5e-6f), 1920, 1080, 256, 64, 1e-6f));
        assertFalse(view.matches(matrix, 1920, 1080, 257, 64, 1e-6f));
        assertFalse(view.matches(matrix, 1920, 1080, 256, 65, 1e-6f));
        assertFalse(view.matches(matrix.m30(1.5e-6f), 1920, 1080, 256, 64, 1e-6f));
    }
}
