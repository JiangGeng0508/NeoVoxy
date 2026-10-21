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
import me.cortex.voxy.testing.GpuTestContext;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static me.cortex.voxy.testing.Reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;

/** Optional real-driver checks; enable voxy.verifyMeshGpu on the test JVM. */
@EnabledIfSystemProperty(named = "voxy.verifyMeshGpu", matches = "true")
class MeshReplacementGpuTest {
    private static GpuTestContext context;

    @BeforeAll
    static void openContext() {
        context = GpuTestContext.open("MeshReplacementGpuTest");
    }

    @AfterAll
    static void closeContext() throws Exception {
        if (context == null) return;
        // DistantMesh's shared index buffer belongs to the GL context too.
        var field = DistantMesh.class.getDeclaredField("sharedIndexBuffer");
        field.setAccessible(true);
        glDeleteBuffers(field.getInt(null));
        field.setInt(null, 0);
        var capacity = DistantMesh.class.getDeclaredField("sharedIndexQuadCapacity");
        capacity.setAccessible(true);
        capacity.setInt(null, 0);
        context.close();
        context = null;
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
        var builder = new DistantMeshBuilder(true);
        try {
            // Subdivide a unit plane so the 1/4 and 1-block grids actually collapse
            // vertices. A 16-block plane with integer vertices has no coarse levels.
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                float x0 = x / 16f, x1 = (x + 1) / 16f;
                float y0 = y / 16f, y1 = (y + 1) / 16f;
                builder.rawVertex(x0, y0, 0, x0, y0, 15, 15, 1, 3, 0xFFFFFF);
                builder.rawVertex(x1, y0, 0, x1, y0, 15, 15, 1, 3, 0xFFFFFF);
                builder.rawVertex(x1, y1, 0, x1, y1, 15, 15, 1, 3, 0xFFFFFF);
                builder.rawVertex(x0, y1, 0, x0, y1, 15, 15, 1, 3, 0xFFFFFF);
            }
            var mesh = builder.build();
            assertTrue(mesh.gpuByteSize() > (long) mesh.quadCount() * 128);
            return mesh;
        } finally {
            builder.discard();
        }
    }

    private static void assertAllocated(DistantMesh mesh, boolean allocated) {
        try {
            assertEquals(allocated, glIsBuffer((int) get(mesh, "vbo")));
            assertEquals(allocated, glIsVertexArray((int) get(mesh, "vao")));
            for (String name : new String[]{"lodBuffer", "coarseVbo"}) {
                int buffer = get(mesh, name);
                if (buffer != 0) assertEquals(allocated, glIsBuffer(buffer), name);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
