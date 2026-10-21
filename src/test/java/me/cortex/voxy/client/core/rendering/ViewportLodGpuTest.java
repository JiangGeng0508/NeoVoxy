package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.gl.GlBuffer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Real Viewport/ViewportSelector updates with their depth targets and Hi-Z shader objects. */
@EnabledIfSystemProperty(named = "voxy.verifyScreenspaceGpu", matches = "true")
class ViewportLodGpuTest {
    private static long window;

    @BeforeAll
    static void openContext() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "Voxy viewport LOD verification", 0, 0);
        assertNotEquals(0L, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("Viewport LOD GPU: " + glGetString(GL_RENDERER));
    }

    @AfterAll
    static void closeContext() {
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Test
    void secondaryTargetUpdatesLeaveMainSelectionAndDepthBufferUntouched() {
        var selector = new ViewportSelector<>(TestViewport::new);
        try {
            var main = selector.getViewport();
            assertTrue(main.isMainViewport);
            assertSame(main, selector.getViewportForSize(1920, 1080));
            configure(main, 1920, 1080, 70).update(256, 256);
            int level = main.lodSelection.level(0, 0, -2048, 16, 16, -2032);
            int revision = main.lodSelection.revision(), evaluations = main.lodSelection.evaluations;
            var mainDepth = main.depthBoundingBuffer.getDepthTex();
            var mainMvp = new Matrix4f(main.MVP);
            for (int[] size : new int[][]{{256, 128}, {1024, 1024}, {256, 128}}) {
                var secondary = selector.getViewportForSize(size[0], size[1]);
                assertNotSame(main, secondary);
                assertFalse(secondary.isMainViewport);
                assertNotSame(main.lodSelection, secondary.lodSelection);
                configure(secondary, size[0], size[1], 7).setCamera(10, 100, -2048).update(28, 128);
                secondary.lodSelection.level(0, 0, -2048, 16, 16, -2032);
                assertSame(secondary, selector.getViewportForSize(size[0], size[1]));
                assertEquals(128, secondary.lodSelection.fullDetailDistance);
                assertEquals(revision, main.lodSelection.revision());
                assertEquals(256, main.lodSelection.fullDetailDistance);
                assertSame(mainDepth, main.depthBoundingBuffer.getDepthTex());
                assertEquals(mainMvp, main.MVP);
                assertEquals(1920, main.width);
                assertEquals(1080, main.height);
            }
            assertSame(main, selector.getViewportForSize(1920, 1080));
            main.update(256, 256);
            assertEquals(level, main.lodSelection.level(0, 0, -2048, 16, 16, -2032));
            assertEquals(evaluations, main.lodSelection.evaluations);

            main.update(256, 4096);
            assertNotEquals(revision, main.lodSelection.revision());
            assertEquals(0, main.lodSelection.level(0, 0, -2048, 16, 16, -2032));
            configure(main, 3840, 2160, 1).update(28, 256);
            assertEquals(0, main.lodSelection.level(0, 0, -2048, 16, 16, -2032));
            assertEquals(3840, main.depthBoundingBuffer.getDepthTex().getWidth());
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            selector.free();
        }
    }

    private static TestViewport configure(TestViewport view, int width, int height, float fov) {
        var projection = new Matrix4f().perspective((float) Math.toRadians(fov), width / (float) height, .1f, 50000);
        return view.setScreenSize(width, height).setProjection(projection).setVanillaProjection(projection);
    }

    private static final class TestViewport extends Viewport<TestViewport> {
        TestViewport() { super(new RenderProperties(false, false, false), false); }
        @Override public GlBuffer getRenderList() { return null; }
    }
}
