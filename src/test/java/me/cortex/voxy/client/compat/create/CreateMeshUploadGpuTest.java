package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.testing.GpuTestContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static me.cortex.voxy.testing.Reflect.get;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;

@EnabledIfSystemProperty(named = "voxy.verifyMeshGpu", matches = "true")
class CreateMeshUploadGpuTest {
    private static GpuTestContext context;
    @BeforeAll static void open() { context = GpuTestContext.open("CreateMeshUploadGpuTest"); }
    @AfterAll static void close() throws Exception {
        if (context == null) return;
        var buffer = DistantMesh.class.getDeclaredField("sharedIndexBuffer"); buffer.setAccessible(true);
        var capacity = DistantMesh.class.getDeclaredField("sharedIndexQuadCapacity"); capacity.setAccessible(true);
        glDeleteBuffers(buffer.getInt(null)); buffer.setInt(null, 0); capacity.setInt(null, 0);
        context.close(); context = null;
    }

    @Test void largeReplacementIsUnpublishedUntilAllVertexAndIndexBytesArrive() throws Exception {
        var manager = new ServiceManager(n -> {});
        var initialCpu = CreateMeshQueueTest.capture(2);
        var visible = new DistantMesh[]{DistantMeshBuilder.upload(initialCpu)};
        int oldBuffer = get(visible[0], "vbo");
        var source = DistantMeshFixtures.tiledPlane(192); // >4 MiB plus LoD/index buffers.
        // Builder's light format stores the centre of each 16-value light bin.
        for (int v = 0; v < source.limit(); v += 32) source.put(v + 20, (byte) 248).put(v + 21, (byte) 248);
        try (var queue = new CreateMeshQueue(manager, () -> 0L, DistantMeshBuilder.CpuMesh::beginUpload, CreateMeshQueue.MAX_BYTES)) {
            queue.request("carriage", () -> {
                var builder = new DistantMeshBuilder(true);
                try { DistantMeshBuilderTest.append(builder, source); return builder.capture(); }
                finally { builder.discard(); }
            }, mesh -> { visible[0].free(); visible[0] = mesh; }, () -> fail("replacement cancelled"));
            queue.frame(); manager.tryRunAJob();
            int frames = 0;
            while (queue.stats().tasks() != 0 && frames++ < 12) {
                queue.frame();
                assertTrue(queue.stats().lastFrameBytes() <= 2L << 20);
                if (queue.stats().tasks() != 0) {
                    assertTrue(glIsBuffer(oldBuffer)); assertEquals(oldBuffer, (int) get(visible[0], "vbo"));
                }
            }
            assertTrue(frames >= 3); assertEquals(0, queue.stats().tasks()); assertFalse(glIsBuffer(oldBuffer));
            var actual = MemoryUtil.memAlloc(source.limit());
            try {
                glGetNamedBufferSubData((int) get(visible[0], "vbo"), 0, actual);
                assertEquals(source, actual);
            } finally { MemoryUtil.memFree(actual); }
            int indexBuffer = get(visible[0], "privateIndexBuffer"); assertNotEquals(0, indexBuffer);
            assertEquals(indexBuffer, glGetVertexArrayi((int) get(visible[0], "vao"), GL_ELEMENT_ARRAY_BUFFER_BINDING));
            assertEquals(source.limit() + visible[0].quadCount() * 24L + (long) get(visible[0], "lodBytes"),
                    visible[0].gpuByteSize());
            int[] actualIndices = new int[visible[0].quadCount() * 6];
            glGetNamedBufferSubData(indexBuffer, 0, actualIndices);
            for (int q = 0; q < visible[0].quadCount(); q++) {
                int b = q * 4, o = q * 6;
                assertArrayEquals(new int[]{b, b + 1, b + 2, b + 2, b + 3, b}, java.util.Arrays.copyOfRange(actualIndices, o, o + 6));
            }
            assertEquals(0, queue.stats().reservedBytes()); assertEquals(GL_NO_ERROR, glGetError());
        } finally { visible[0].free(); manager.shutdown(); }
    }

    @Test void stagedCoarseGeometryMatchesSynchronousPreparationByteForByte() throws Exception {
        var fixture = DistantMeshFixtures.fixtures().getFirst();
        var builder = new DistantMeshBuilder(true);
        DistantMeshBuilder.CpuMesh cpu = null;
        DistantMesh staged = null;
        try {
            DistantMeshBuilderTest.append(builder, fixture.data()); cpu = builder.capture(); cpu.prepare();
            DistantMeshLod lod = get(cpu, "lod"); assertNotNull(lod.vertices);
            try (var upload = cpu.beginUpload()) {
                while (!upload.done()) assertTrue(upload.step(4096) <= 4096);
                staged = upload.finish();
            }
            int coarse = get(staged, "coarseVbo"); long offset = 0;
            for (int[] expected : lod.vertices) {
                int[] actual = new int[expected.length]; glGetNamedBufferSubData(coarse, offset, actual);
                assertArrayEquals(expected, actual); offset += expected.length * 4L;
            }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { if (staged != null) staged.free(); if (cpu != null) cpu.free(); builder.discard(); }
    }

    @Test void cancellingAnUploadDeletesAllUnpublishedGpuBuffers() throws Exception {
        var cpu = CreateMeshQueueTest.capture(64); cpu.prepare();
        var upload = cpu.beginUpload();
        DistantMesh mesh = get(upload, "mesh");
        int vbo = get(mesh, "vbo"), lod = get(mesh, "lodBuffer"), indices = get(mesh, "privateIndexBuffer");
        try {
            upload.step(4096); assertFalse(upload.done()); assertTrue(glIsBuffer(vbo));
            upload.close();
            assertFalse(glIsBuffer(vbo)); if (lod != 0) assertFalse(glIsBuffer(lod));
            if (indices != 0) assertFalse(glIsBuffer(indices));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { upload.close(); cpu.free(); }
    }
}
