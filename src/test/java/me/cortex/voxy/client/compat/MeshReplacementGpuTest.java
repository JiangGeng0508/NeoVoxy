package me.cortex.voxy.client.compat;

import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.client.compat.domum.DomumDistantRenderer;
import me.cortex.voxy.client.core.beacon.BeaconBeamSolver;
import me.cortex.voxy.client.core.beacon.DistantBeaconRenderer;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static me.cortex.voxy.testing.Reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Optional real-driver checks; enable voxy.verifyMeshGpu on the test JVM. */
@EnabledIfSystemProperty(named = "voxy.verifyMeshGpu", matches = "true")
class MeshReplacementGpuTest {
    private static long window;

    @BeforeAll
    static void openContext() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "Voxy mesh replacement verification", 0, 0);
        assertNotEquals(0L, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("Mesh replacement GPU: " + glGetString(GL_RENDERER));
    }

    @AfterAll
    static void closeContext() throws Exception {
        // DistantMesh's shared index buffer belongs to the GL context too.
        var field = DistantMesh.class.getDeclaredField("sharedIndexBuffer");
        field.setAccessible(true);
        glDeleteBuffers(field.getInt(null));
        field.setInt(null, 0);
        var capacity = DistantMesh.class.getDeclaredField("sharedIndexQuadCapacity");
        capacity.setAccessible(true);
        capacity.setInt(null, 0);
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Test
    void domumKeepsOldGpuObjectsUntilReplacementAndReleasesExplicitDeletions() throws Exception {
        var renderer = new DomumDistantRenderer();
        long key = BlockPos.asLong(0, 0, 0);
        try {
            call(renderer, "install", key, data(1), true);
            Map<Long, Object> entries = get(renderer, "sections");
            Object entry = entries.get(key);
            DistantMesh old = mesh();
            set(entry, "mesh", old);
            set(entry, "needsBake", false);
            call(renderer, "install", key, data(2), true);
            assertSame(old, get(entry, "mesh"));
            assertAllocated(old, true);
            call(renderer, "rebuild", entry, 1000L, (Supplier<DistantMesh>) () -> null);
            assertSame(old, get(entry, "mesh"));
            call(renderer, "rebuild", entry, 2000L, (Supplier<DistantMesh>) () -> {
                assertAllocated(old, true);
                throw new IllegalStateException("expected GPU test bake failure");
            });
            assertAllocated(old, true);
            DistantMesh replacement = mesh();
            call(renderer, "rebuild", entry, 3000L, (Supplier<DistantMesh>) () -> {
                assertAllocated(old, true);
                return replacement;
            });
            assertSame(replacement, get(entry, "mesh"));
            assertAllocated(old, false);
            assertAllocated(replacement, true);
            call(renderer, "refreshDrawable");
            assertEquals(1, ((List<?>) get(renderer, "drawable")).size());
            call(renderer, "install", key, null, true);
            assertAllocated(replacement, false);
            call(renderer, "refreshDrawable");
            assertTrue(((List<?>) get(renderer, "drawable")).isEmpty());
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            renderer.logout(null);
            clearStatic(DomumDistantRenderer.class, "active");
            DomumOrnamentumCompat.sectionListener = null;
        }
    }

    @Test
    void beaconKeepsOldBeamDuringLookupAndBakeFailuresButClearsConfirmedEmptyBeams() throws Exception {
        var renderer = new DistantBeaconRenderer();
        long pos = BlockPos.asLong(0, 64, 0);
        var segments = List.of(new BeaconBeamSolver.Segment(0xFFFFFF, 65, 1088));
        var complete = new BeaconBeamSolver.Result(segments, false, false);
        try {
            DistantMesh old = mesh();
            call(renderer, "applyResult", pos, 64, 1000L, complete, (Supplier<DistantMesh>) () -> old);
            Map<Long, Object> states = get(renderer, "states");
            Object state = states.get(pos);
            call(renderer, "applyResult", pos, 64, 2000L,
                    new BeaconBeamSolver.Result(List.of(), true, false), (Supplier<DistantMesh>) () -> fail());
            assertSame(old, get(state, "mesh"));
            assertEquals(1088.0, (double) get(state, "topY"));
            call(renderer, "applyResult", pos, 64, 3000L,
                    new BeaconBeamSolver.Result(List.of(), false, true), (Supplier<DistantMesh>) () -> fail());
            call(renderer, "applyResult", pos, 64, 4000L, complete, (Supplier<DistantMesh>) () -> null);
            call(renderer, "applyResult", pos, 64, 5000L, complete, (Supplier<DistantMesh>) () -> {
                throw new IllegalStateException("expected GPU test bake failure");
            });
            assertSame(old, get(state, "mesh"));
            assertAllocated(old, true);
            DistantMesh replacement = mesh();
            call(renderer, "applyResult", pos, 64, 6000L, complete, (Supplier<DistantMesh>) () -> {
                assertAllocated(old, true);
                return replacement;
            });
            assertAllocated(old, false);
            assertAllocated(replacement, true);
            call(renderer, "applyResult", pos, 64, 7000L,
                    new BeaconBeamSolver.Result(List.of(), false, false), (Supplier<DistantMesh>) () -> fail());
            assertNull(get(state, "mesh"));
            assertAllocated(replacement, false);
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            renderer.onLogout(null);
            clearStatic(DistantBeaconRenderer.class, "active");
        }
    }

    private static byte[] data(int variant) {
        return ByteBuffer.allocate(9).put((byte) 1).putShort((short) 1).putShort((short) 0).putInt(variant).array();
    }

    private static DistantMesh mesh() {
        var builder = new DistantMeshBuilder();
        try {
            builder.rawVertex(0, 0, 0, 0, 0, 15, 15, 1, 0, 0xFFFFFF);
            builder.rawVertex(1, 0, 0, 1, 0, 15, 15, 1, 0, 0xFFFFFF);
            builder.rawVertex(1, 1, 0, 1, 1, 15, 15, 1, 0, 0xFFFFFF);
            builder.rawVertex(0, 1, 0, 0, 1, 15, 15, 1, 0, 0xFFFFFF);
            return builder.build();
        } finally {
            builder.discard();
        }
    }

    private static void assertAllocated(DistantMesh mesh, boolean allocated) {
        try {
            assertEquals(allocated, glIsBuffer((int) get(mesh, "vbo")));
            assertEquals(allocated, glIsVertexArray((int) get(mesh, "vao")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
