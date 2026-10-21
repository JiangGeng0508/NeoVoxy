package me.cortex.voxy.client.compat.littletiles;

import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.client.compat.create.DistantMeshBuilder;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static me.cortex.voxy.testing.Reflect.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Exercises the real CPU bake, paired GPU upload and asynchronous result ownership. */
@EnabledIfSystemProperty(named = "voxy.verifyMeshGpu", matches = "true")
class LittleTilesUploadGpuTest {
    private static long window;
    private final LittleTilesDistantRenderer renderer = new LittleTilesDistantRenderer();

    @BeforeAll static void openContext() {
        assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "LittleTiles upload verification", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        System.out.println("LittleTiles upload GPU: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
    }

    @AfterEach void cleanup() throws Exception {
        renderer.logout(null);
        clearStatic(LittleTilesDistantRenderer.class, "active");
        assertEquals(GL_NO_ERROR, glGetError());
    }

    @AfterAll static void closeContext() throws Exception {
        for (String name : new String[]{"sharedIndexBuffer", "sharedIndexQuadCapacity"}) {
            var field = DistantMesh.class.getDeclaredField(name);
            field.setAccessible(true);
            if (name.equals("sharedIndexBuffer")) glDeleteBuffers(field.getInt(null));
            field.setInt(null, 0);
        }
        GL.setCapabilities(null);
        glfwMakeContextCurrent(0);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    @Test void completedBakeUploadsBothLayersAndSnapshotReplacementFreesEveryLodBuffer() throws Exception {
        var snapshot = snapshot();
        Object entry = install(snapshot);
        Object cpu = cpu();
        DistantMeshBuilder.CpuMesh opaqueCpu = get(cpu, "opaque"), glassCpu = get(cpu, "translucent");
        complete(snapshot, cpu, 0);
        DistantMesh opaque = get(entry, "opaqueMesh"), glass = get(entry, "translucentMesh");
        assertNotNull(opaque); assertNotNull(glass);
        assertTrue(((float[]) get(opaque, "grids")).length > 0);
        assertEquals(0, ((float[]) get(glass, "grids")).length);
        assertEquals(0, opaqueCpu.byteSize()); assertEquals(0, glassCpu.byteSize());
        assertAllocated(opaque, true); assertAllocated(glass, true);

        Object replacement = install(snapshot());
        assertNotSame(entry, replacement);
        assertAllocated(opaque, false); assertAllocated(glass, false);
        complete(get(replacement, "snapshot"), cpu(), 0);
        DistantMesh newOpaque = get(replacement, "opaqueMesh"), newGlass = get(replacement, "translucentMesh");
        renderer.logout(null);
        assertAllocated(newOpaque, false); assertAllocated(newGlass, false);
    }

    @Test void secondUploadFailurePreservesOldPairAndConsumesAllCpuData() throws Exception {
        var snapshot = snapshot();
        Object entry = install(snapshot);
        complete(snapshot, cpu(), 0);
        DistantMesh oldOpaque = get(entry, "opaqueMesh"), oldGlass = get(entry, "translucentMesh");
        Object replacement = cpu();
        DistantMeshBuilder.CpuMesh opaque = get(replacement, "opaque"), glass = get(replacement, "translucent");
        glass.free(); // Fail the second upload after the new opaque GPU objects have been allocated.
        assertThrows(IllegalStateException.class, () -> call(renderer, "upload", entry, replacement));
        assertEquals(0, opaque.byteSize()); assertEquals(0, glass.byteSize());
        assertSame(oldOpaque, get(entry, "opaqueMesh")); assertSame(oldGlass, get(entry, "translucentMesh"));
        assertAllocated(oldOpaque, true); assertAllocated(oldGlass, true);
        complete(snapshot, cpu(), 0);
        assertAllocated(oldOpaque, false); assertAllocated(oldGlass, false);
    }

    @Test void staleSnapshotAndPreviousWorldResultsCannotReplaceCurrentMeshes() throws Exception {
        var oldSnapshot = snapshot();
        install(oldSnapshot);
        var current = snapshot();
        Object entry = install(current);
        complete(current, cpu(), 0);
        DistantMesh opaque = get(entry, "opaqueMesh"), glass = get(entry, "translucentMesh");
        for (boolean oldWorld : new boolean[]{false, true}) {
            Object stale = cpu();
            DistantMeshBuilder.CpuMesh staleOpaque = get(stale, "opaque"), staleGlass = get(stale, "translucent");
            complete(oldWorld ? current : oldSnapshot, stale, oldWorld ? -1 : 0);
            assertEquals(0, staleOpaque.byteSize()); assertEquals(0, staleGlass.byteSize());
            assertSame(opaque, get(entry, "opaqueMesh")); assertSame(glass, get(entry, "translucentMesh"));
            assertAllocated(opaque, true); assertAllocated(glass, true);
        }
    }

    @Test void failedWorkerBakeIsRetriedWithoutMovingTheCamera() throws Exception {
        var snapshot = snapshot();
        Object entry = install(snapshot);
        call(renderer, "refreshCandidates", 8d, 72d, -1016d, 64d);
        ((Queue<?>) get(renderer, "bakeQueue")).clear();
        ((Set<?>) get(renderer, "queued")).clear();
        set(renderer, "candidatesDirty", false);
        complete(snapshot, null, 0);
        assertNull(get(entry, "opaqueMesh"));
        call(renderer, "refreshCandidates", 8d, 72d, -1016d, 64d);
        assertEquals(1, ((Queue<?>) get(renderer, "bakeQueue")).size());
        assertEquals(0, (int) get(renderer, "bakesInFlight"));
        assertTrue(((Set<?>) get(renderer, "baking")).isEmpty());
    }

    private Object install(LittleTilesCompat.SectionSnapshot snapshot) throws Exception {
        LittleTilesDistantRenderer.accept(null, snapshot);
        call(renderer, "drainUpdates", 10, null, 0d);
        Map<Long, Object> entries = get(renderer, "sections");
        return entries.get(LittleTilesStore.key(snapshot.sx(), snapshot.sy(), snapshot.sz()));
    }

    private void complete(LittleTilesCompat.SectionSnapshot snapshot, Object cpu, int generation) throws Exception {
        long key = LittleTilesStore.key(snapshot.sx(), snapshot.sy(), snapshot.sz());
        var type = Class.forName(LittleTilesDistantRenderer.class.getName() + "$BakeResult");
        var ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Queue<Object> results = get(renderer, "completedBakes");
        results.add(ctor.newInstance(key, generation, snapshot, cpu));
        Set<Long> baking = get(renderer, "baking");
        baking.add(key);
        set(renderer, "bakesInFlight", 1);
        call(renderer, "uploadCompleted", 8d, 72d, -1016d, 4096d);
    }

    private static LittleTilesCompat.SectionSnapshot snapshot() {
        var cells = new ArrayList<LittleTilesCompat.Cell>();
        for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++)
            cells.add(new LittleTilesCompat.Cell(x | z << 7 | y << 14, 0, 0xA3));
        cells.add(new LittleTilesCompat.Cell(10, 1, 0xA3));
        return LittleTilesMeshTest.snapshot(cells);
    }

    private static Object cpu() throws Exception { return LittleTilesMeshTest.bake(snapshot().cells()); }

    private static void assertAllocated(DistantMesh mesh, boolean expected) throws Exception {
        assertEquals(expected, glIsVertexArray(get(mesh, "vao")));
        for (String name : new String[]{"vbo", "lodBuffer", "coarseVbo"}) {
            int id = get(mesh, name);
            if (id != 0) assertEquals(expected, glIsBuffer(id), name);
        }
    }
}
