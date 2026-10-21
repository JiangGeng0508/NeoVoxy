package me.cortex.voxy.client.compat.create;
import me.cortex.voxy.testing.GpuTestContext;
import org.lwjgl.system.MemoryUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.lwjgl.opengl.GL45C.*;
import static org.junit.jupiter.api.Assertions.*;
/** Adapted reference GPU checks, using the actual shipped vertex shader. */
@EnabledIfSystemProperty(named="voxy.verifyMeshGpu", matches="true")
class DistantMeshLodGpuTest {
    @Test void frameStatisticsMatchGpuSubmissionsCullingAndDepthReplay() throws Exception {
        int shader = compile(GL_VERTEX_SHADER, resource("distant.vert")), program = glCreateProgram();
        glAttachShader(program, shader); glLinkProgram(program);
        assertNotEquals(0, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        int query = glGenQueries();
        var builder = DistantPolylineLodTest.builder(DistantPolylineLodTest.curve());
        var mesh = builder.buildPolyline();
        try {
            glUseProgram(program); glEnable(GL_RASTERIZER_DISCARD);
            var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(70), 16f / 9, .1f, 50000);
            var main = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
            var secondary = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
            main.update(projection, projection, 1920, 1080, 0, 0, 0, 256, 96);
            secondary.update(projection, projection, 256, 128, 0, 0, 0, 256, 96);
            glUniformMatrix4fv(0, false, projection.get(new float[16]));
            DistantMesh.beginFrame();
            glBeginQuery(GL_PRIMITIVES_GENERATED, query);
            mesh.drawAt(main, 0, 0, -32);
            assertEquals(0, mesh.lastDrawLevel());
            mesh.drawAt(secondary, 0, 0, -2048);
            assertTrue(mesh.lastDrawLevel() > 0);
            int coarseLevel = mesh.lastDrawLevel();
            mesh.drawTranslucent(main, new org.joml.Matrix4f(projection).translate(0, 0, -4096).scale(.000001f), 1920, 1080);
            var beforeReplay = DistantMesh.frameStatistics();
            mesh.drawDepth(coarseLevel, 0);
            glEndQuery(GL_PRIMITIVES_GENERATED);
            var stats = DistantMesh.frameStatistics();
            assertEquals(glGetQueryObjecti(query, GL_QUERY_RESULT), stats.submittedTriangles());
            assertEquals(mesh.quadCount() * 2L * 4, stats.fullDetailTriangles());
            assertEquals(2, stats.coarseDraws()); assertEquals(1, stats.detailedDraws()); assertEquals(1, stats.culledDraws());
            assertEquals(stats.submittedTriangles() - beforeReplay.submittedTriangles(), stats.depthReplayTriangles());
            assertEquals(0, beforeReplay.depthReplayTriangles(), "snapshots remain immutable");
            assertThrows(IllegalArgumentException.class, () -> mesh.draw(-1, 0));
            assertEquals(stats, DistantMesh.frameStatistics(), "invalid calls must not count as submitted draws");
            var debug = new java.util.ArrayList<String>(); DistantMesh.addDebug(debug);
            assertTrue(debug.getFirst().contains(stats.submittedTriangles() + "/" + stats.fullDetailTriangles()));
            DistantMesh.beginFrame();
            assertEquals(new DistantMesh.FrameStatistics(0, 0, 0, 0, 0, 0), DistantMesh.frameStatistics(), "an empty frame clears stale data");
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            mesh.free(); builder.discard(); glDisable(GL_RASTERIZER_DISCARD); glUseProgram(0);
            glDeleteQueries(query); glDeleteProgram(program); glDeleteShader(shader); DistantMesh.beginFrame();
        }
    }

    @Test void beaconAndLaserKeepShapeAndAttributesWithPlainZeroAndNonzeroTaa() throws Exception {
        var laserBake = me.cortex.voxy.client.compat.simulated.DistantLaserRenderer.class.getDeclaredMethod("bakeUnitBeam");
        laserBake.setAccessible(true);
        var beaconBake = me.cortex.voxy.client.core.beacon.DistantBeaconRenderer.class.getDeclaredMethod("bake", java.util.List.class, int.class);
        beaconBake.setAccessible(true);
        var laser = (DistantMesh) laserBake.invoke(null);
        var beacon = (DistantMesh) beaconBake.invoke(null, java.util.List.of(
                new me.cortex.voxy.client.core.beacon.BeaconBeamSolver.Segment(0x123456, 64, 576),
                new me.cortex.voxy.client.core.beacon.BeaconBeamSolver.Segment(0xABCDEF, 576, 1088)), 64);
        int output = glCreateBuffers(), query = glGenQueries();
        try {
            assertNotNull(laser); assertNotNull(beacon);
            assertEquals(4, laser.quadCount()); assertEquals(8, beacon.quadCount());
            for (var mesh : new DistantMesh[]{laser, beacon}) {
                assertEquals(mesh.quadCount() * 128L, mesh.gpuByteSize(), "beams have no coarse cache");
            }
            glEnable(GL_RASTERIZER_DISCARD);
            glNamedBufferData(output, 8L * 6 * 12 * 4, GL_STREAM_READ);
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, output);
            for (int variant = 0; variant < 3; variant++) {
                boolean patched = variant != 0;
                float shift = variant == 2 ? .015625f : 0;
                String header = "#version 460 core\n" + (patched ? "#define PATCHED_SHADER\n#define TRANSLUCENT\n" : "");
                String vs = resource("distant.vert").replace("#version 460 core", header)
                        + (patched ? "\nvec2 distantTaaShift() { return vec2(" + shift + ", " + -shift + "); }" : "");
                String fs = resource("distant_laser.frag").replace("#version 460 core", header)
                        + (patched ? "\nlayout(location=0) out vec4 resultColour;\nvoid voxy_emitFragment(VoxyFragmentParameters p) { resultColour = p.sampledColour; }" : "");
                int vertex = compile(GL_VERTEX_SHADER, vs), fragment = compile(GL_FRAGMENT_SHADER, fs), program = glCreateProgram();
                try {
                    glAttachShader(program, vertex); glAttachShader(program, fragment);
                    glTransformFeedbackVaryings(program, new String[]{"gl_Position", "fColor", "fUv", "fLightUv"}, GL_INTERLEAVED_ATTRIBS);
                    glLinkProgram(program);
                    assertNotEquals(0, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
                    glUseProgram(program);
                    var normal = new org.joml.Matrix4f().perspective((float)Math.toRadians(70), 16f/9, .1f, 50000);
                    var zoom = new org.joml.Matrix4f().perspective((float)Math.toRadians(.001), 16f/9, .1f, 50000);
                    var tall = new org.joml.Matrix4f().translate(0, -512, -4096).scale(.001f, 1, .001f);
                    var longLaser = new org.joml.Matrix4f().translate(0, 64, -4096).rotateX((float)Math.PI / 2).scale(.0001f, .0001f, 128);
                    var tiny = new org.joml.Matrix4f().translate(0, 0, -4096).scale(.0001f, .0001f, .001f);
                    checkThinDraw(beacon, normal, tall, 16, shift, output, query);
                    checkThinDraw(laser, normal, longLaser, 8, shift, output, query);
                    checkThinDraw(laser, normal, tiny, 0, shift, output, query);
                    checkThinDraw(laser, zoom, tiny, 8, shift, output, query);
                    checkThinDraw(laser, normal, tiny, 0, shift, output, query);
                } finally { glUseProgram(0); glDeleteProgram(program); glDeleteShader(vertex); glDeleteShader(fragment); }
            }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            glDisable(GL_RASTERIZER_DISCARD);
            if (laser != null) laser.free(); if (beacon != null) beacon.free();
            glDeleteBuffers(output); glDeleteQueries(query);
        }
    }

    private static void checkThinDraw(DistantMesh mesh, org.joml.Matrix4f projection, org.joml.Matrix4f model,
                                       int triangles, float shift, int output, int query) throws Exception {
        var selection = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
        selection.update(projection, projection, 1920, 1080, 0, 0, 0, 256, 96);
        var transform = new org.joml.Matrix4f(projection).mul(model);
        glUniformMatrix4fv(0, false, transform.get(new float[16]));
        glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, query); glBeginTransformFeedback(GL_TRIANGLES);
        mesh.drawTranslucent(selection, transform, 1920, 1080);
        glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
        assertEquals(triangles, glGetQueryObjecti(query, GL_QUERY_RESULT));
        if (triangles == 0) return;
        assertEquals(0, mesh.lastDrawLevel());
        int[] source = new int[mesh.quadCount() * 32];
        glGetNamedBufferSubData((int)me.cortex.voxy.testing.Reflect.get(mesh, "vbo"), 0, source);
        float[] actual = new float[triangles * 3 * 12]; glGetNamedBufferSubData(output, 0, actual);
        int[] indices = fullIndices(mesh.quadCount());
        for (int i = 0; i < indices.length; i++) {
            int v = indices[i] * 8, o = i * 12;
            var expected = transform.transform(new org.joml.Vector4f(Float.intBitsToFloat(source[v]),
                    Float.intBitsToFloat(source[v + 1]), Float.intBitsToFloat(source[v + 2]), 1));
            expected.x += shift * expected.w; expected.y -= shift * expected.w;
            for (int axis = 0; axis < 4; axis++) assertEquals(expected.get(axis), actual[o + axis], .001f);
            for (int c = 0; c < 4; c++) assertEquals(((source[v + 6] >>> (c * 8)) & 255) / 255f, actual[o + 4 + c], 1e-6);
            assertEquals(Float.intBitsToFloat(source[v + 3]), actual[o + 8]);
            assertEquals(Float.intBitsToFloat(source[v + 4]), actual[o + 9]);
            assertEquals((source[v + 5] & 255) / 255f, actual[o + 10], 1e-6);
            assertEquals(((source[v + 5] >>> 8) & 255) / 255f, actual[o + 11], 1e-6);
        }
    }

    @Test void trainDepthRecordsKeepEachInstancesLevelBlendAndAnimatedTransform() throws Exception {
        var renderer = new DistantTrainRenderer();
        var begin = DistantTrainRenderer.class.getDeclaredMethod("beginDepthFrame", me.cortex.voxy.client.core.rendering.Viewport.class);
        begin.setAccessible(true);
        var recordsField = DistantTrainRenderer.class.getDeclaredField("DEPTH_DRAWS");
        recordsField.setAccessible(true);
        var records = (java.util.List<?>) recordsField.get(null);
        String vertex = resource("distant.vert").replace("#version 460 core", "#version 460 core\n#define TRAIN_DEPTH_REPLAY")
                + "\nvec2 distantTaaShift() { return vec2(.015625, -.015625); }";
        int shader = compile(GL_VERTEX_SHADER, vertex), program = glCreateProgram();
        glAttachShader(program, shader);
        glTransformFeedbackVaryings(program, new String[]{"gl_Position", "fLodClip"}, GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        assertNotEquals(0, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        int output = glCreateBuffers(), query = glGenQueries();
        var builder = new DistantMeshBuilder(true);
        DistantMesh mesh = null;
        try {
            DistantMeshBuilderTest.append(builder, DistantMeshFixtures.tiledPlane(64));
            mesh = builder.build();
            glUseProgram(program); glEnable(GL_RASTERIZER_DISCARD);
            glNamedBufferData(output, mesh.quadCount() * 6L * 8 * 4, GL_STREAM_READ);
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER, 0, output);
            var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(70), 16f/9, .1f, 50000);
            var selection = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
            selection.update(projection, projection, 1920, 1080, 1024, 64, -2048, 256, 96);
            begin.invoke(null, new Object[]{null});
            var expected = new java.util.ArrayList<float[]>();
            for (int i = 0; i < 3; i++) {
                var model = new org.joml.Matrix4f().translate(i - 1, .5f, i == 1 ? -48 : -4096)
                        .rotateY(i * .3f).rotateZ(i * -.1f).translate(-.5f, -.5f, -.5f);
                mesh.drawModel(selection, model, 1024, 64, -2048);
                if (i == 1) assertEquals(0, mesh.lastDrawLevel(), "near handoff keeps all detail");
                else assertTrue(mesh.lastDrawLevel() > 0, "far animated model coarsens");
                int level = mesh.lastDrawLevel();
                mesh.draw(level, i == 0 ? .375f : 0);
                me.cortex.voxy.testing.Reflect.call(renderer, "recordDepthDraw", mesh, model);
                var transform = new org.joml.Matrix4f(projection).mul(model);
                glUniformMatrix4fv(0, false, transform.get(new float[16]));
                glUniformMatrix4fv(8, false, transform.get(new float[16]));
                expected.add(capturePositions(mesh, level, mesh.lastDrawBlend(), output, query));
                model.identity(); // Renderer reuses its scratch model for subsequent instances.
            }
            mesh.draw(); // Last mesh state cannot serve as the replay record.
            for (int i = 0; i < 3; i++) {
                var record = records.get(i);
                org.joml.Matrix4f model = me.cortex.voxy.testing.Reflect.get(record, "model");
                var transform = new org.joml.Matrix4f(projection).mul(model);
                glUniformMatrix4fv(0, false, transform.get(new float[16]));
                glUniformMatrix4fv(8, false, transform.get(new float[16]));
                assertSame(mesh, me.cortex.voxy.testing.Reflect.get(record, "mesh"));
                assertArrayEquals(expected.get(i), capturePositions(mesh,
                        me.cortex.voxy.testing.Reflect.get(record, "level"),
                        me.cortex.voxy.testing.Reflect.get(record, "blend"), output, query));
            }
            begin.invoke(null, new Object[]{null});
            for (var record : records) assertNull(me.cortex.voxy.testing.Reflect.get(record, "mesh"));
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            begin.invoke(null, new Object[]{null});
            builder.discard(); if (mesh != null) mesh.free();
            glDisable(GL_RASTERIZER_DISCARD); glUseProgram(0);
            glDeleteBuffers(output); glDeleteQueries(query); glDeleteProgram(program); glDeleteShader(shader);
        }
    }

    private static float[] capturePositions(DistantMesh mesh, int level, float blend, int output, int query) {
        glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN, query); glBeginTransformFeedback(GL_TRIANGLES);
        mesh.draw(level, blend);
        glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
        int triangles = glGetQueryObjecti(query, GL_QUERY_RESULT);
        assertTrue(triangles > 0);
        float[] result = new float[triangles * 3 * 8];
        glGetNamedBufferSubData(output, 0, result);
        return result;
    }

    @Test void sharedIndexGrowthKeepsOldVaosAndFreeReleasesAllPrivateLodBuffers() throws Exception {
        DistantMesh plane = null, volume = null, larger = null;
        var builder = new DistantMeshBuilder(true);
        try {
            DistantMeshBuilderTest.append(builder, DistantMeshFixtures.tiledPlane(64));
            var cpu = builder.assemble();
            int cpuBytes = cpu.byteSize();
            plane = DistantMeshBuilder.upload(cpu);
            assertEquals(0, cpu.byteSize());
            assertEquals(cpuBytes, plane.gpuByteSize());
            int vao = me.cortex.voxy.testing.Reflect.get(plane, "vao");
            int shared = glGetVertexArrayi(vao, GL_ELEMENT_ARRAY_BUFFER_BINDING);
            assertTrue(glIsBuffer(shared));
            int capacity = (int) (glGetNamedBufferParameteri64(shared, GL_BUFFER_SIZE) / 24);
            var data = MemoryUtil.memCalloc((capacity + 1) * 128);
            try { larger = new DistantMesh(data, capacity + 1, DistantMeshLod.EMPTY); }
            finally { MemoryUtil.memFree(data); }
            assertEquals(shared, glGetVertexArrayi(vao, GL_ELEMENT_ARRAY_BUFFER_BINDING));
            assertEquals(shared, glGetVertexArrayi((int) me.cortex.voxy.testing.Reflect.get(larger, "vao"), GL_ELEMENT_ARRAY_BUFFER_BINDING));
            assertTrue(glGetNamedBufferParameteri64(shared, GL_BUFFER_SIZE) >= (capacity + 1) * 24L);
            var fixture = DistantMeshFixtures.fixtures().getFirst();
            var source = fixture.data();
            var direct = MemoryUtil.memAlloc(source.remaining());
            try {
                direct.put(source.duplicate()).flip();
                volume = new DistantMesh(direct, direct.remaining()/128, DistantMeshLod.build(direct,direct.remaining()/128));
            } finally { MemoryUtil.memFree(direct); }
            assertTrue((int) me.cortex.voxy.testing.Reflect.get(plane, "lodBuffer") != 0);
            assertTrue((int) me.cortex.voxy.testing.Reflect.get(volume, "coarseVbo") != 0);
            for (var mesh : new DistantMesh[]{plane, volume, larger}) {
                int meshVao = me.cortex.voxy.testing.Reflect.get(mesh, "vao");
                int vbo = me.cortex.voxy.testing.Reflect.get(mesh, "vbo");
                int indices = me.cortex.voxy.testing.Reflect.get(mesh, "lodBuffer");
                int coarse = me.cortex.voxy.testing.Reflect.get(mesh, "coarseVbo");
                assertTrue(glIsVertexArray(meshVao)); assertTrue(glIsBuffer(vbo));
                mesh.free();
                assertFalse(glIsVertexArray(meshVao)); assertFalse(glIsBuffer(vbo));
                assertFalse(glIsBuffer(indices)); assertFalse(glIsBuffer(coarse));
                int unrelated = glCreateBuffers();
                mesh.free();
                assertTrue(glIsBuffer(unrelated), "double-free must not delete a recycled GL name");
                glDeleteBuffers(unrelated);
                assertThrows(IllegalStateException.class, mesh::draw);
            }
            assertTrue(glIsBuffer(shared), "shared buffer belongs to the context, not an individual mesh");
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            builder.discard();
            if (plane != null) plane.free(); if (volume != null) volume.free(); if (larger != null) larger.free();
        }
    }

    @Test void failedUploadConsumesCpuDataAndAFreshMeshCanStillBeUploaded() throws Exception {
        var builder = new DistantMeshBuilder(true);
        try {
            DistantMeshBuilderTest.append(builder, DistantMeshFixtures.tiledPlane(16));
            var cpu = builder.assemble();
            me.cortex.voxy.testing.Reflect.set(cpu, "lod", null);
            assertThrows(NullPointerException.class, () -> DistantMeshBuilder.upload(cpu));
            assertEquals(0, cpu.byteSize());
            assertThrows(IllegalStateException.class, () -> DistantMeshBuilder.upload(cpu));
            var replacement = new DistantMeshBuilder();
            try {
                DistantMeshBuilderTest.append(replacement, DistantMeshFixtures.tiledPlane(2));
                var mesh = replacement.build();
                assertTrue(glIsBuffer((int) me.cortex.voxy.testing.Reflect.get(mesh, "vbo")));
                mesh.free();
            } finally { replacement.discard(); }
            assertEquals(GL_NO_ERROR, glGetError());
        } finally { builder.discard(); }
    }

    private static GpuTestContext context;
    @BeforeAll static void openContext() {
        context = GpuTestContext.open("DistantMeshLodGpuTest");
    }
    @AfterAll static void closeContext() throws Exception {
        if (context == null) return;
        var buffer=DistantMesh.class.getDeclaredField("sharedIndexBuffer");
        var capacity=DistantMesh.class.getDeclaredField("sharedIndexQuadCapacity");
        buffer.setAccessible(true); capacity.setAccessible(true);
        glDeleteBuffers(buffer.getInt(null)); buffer.setInt(null,0); capacity.setInt(null,0);
        context.close();
        context = null;
    }
    private static String resource(String name) throws Exception {
        try (var input=DistantMeshLodGpuTest.class.getResourceAsStream("/assets/voxy/shaders/compat/"+name)) {
            assertNotNull(input); return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }


@Test
    void verifyZoomDraws() throws Exception {
        String vertex = resource("distant.vert");
        int v = compile(GL_VERTEX_SHADER,vertex), program = glCreateProgram();
        glAttachShader(program,v);
        glTransformFeedbackVaryings(program,new String[]{"gl_Position","fColor","fCustomId"},GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        if (glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        glUseProgram(program);
        var data = MemoryUtil.memAlloc(4096*4*32);
        data.put(DistantMeshFixtures.tiledPlane(64)).flip();
        for (int i=0;i<4096*4;i++) {
            data.put(i*32+24,(byte) 220).put(i*32+25,(byte) 70).put(i*32+26,(byte) 35).put(i*32+27,(byte) 96);
            data.putInt(i*32+28,73);
        }
        var mesh = new DistantMesh(data,4096,DistantMeshLod.build(data,4096));
        mesh.maxX=mesh.maxY=1;
        MemoryUtil.memFree(data);
        int query=glGenQueries(), output=glCreateBuffers();
        glNamedBufferData(output,4096L*6*9*4,GL_STREAM_READ);
        glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
        glEnable(GL_RASTERIZER_DISCARD);
        float[] matrix=new float[16];
        int checks=0;
        var selection = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
        for (int width : new int[]{1920,3840}) {
            long previous=0;
            for (float fov : new float[]{70,30,7,3,1,.5f,.1f,.005f}) {
                var projection=new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
                var m=new org.joml.Matrix4f(projection).translate(-.5f,-.5f,-9000);
                selection.update(projection,projection,width,width*9/16,0,0,0,256,64);
                glUniformMatrix4fv(0,false,m.get(matrix));

                glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                glBeginTransformFeedback(GL_TRIANGLES);
                mesh.drawAt(selection,-.5,-.5,-9000);
                glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                int triangles=glGetQueryObjecti(query,GL_QUERY_RESULT);
                if (triangles<previous) throw new AssertionError("Zoom lost detail");
                if (fov==70 && triangles>=8192) throw new AssertionError("Far mesh was not simplified");
                if (fov==.005f && (triangles!=8192 || mesh.lastDrawBlend()!=0)) throw new AssertionError("Zoom did not restore full mesh");
                previous=triangles;
                checks++;
            }
        }
        // 透明网格缩小时剔除、放大时恢复原始索引顺序与逐顶点颜色/透明度。
        for (float quality : new float[]{28,256,1024})
        for (float nearDetail : new float[]{64,8192})
        for (float fov : new float[]{70,7,70,7}) {
            var m=new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000)
                    .translate(-.5f,-.5f,-4096);
            glUniformMatrix4fv(0,false,m.get(matrix));
            selection.update(m,m,1920,1080,0,0,0,quality,nearDetail);

            glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
            glBeginTransformFeedback(GL_TRIANGLES);
            mesh.drawTranslucent(selection,m,1920,1080);
            glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
            int triangles=glGetQueryObjecti(query,GL_QUERY_RESULT);
            boolean subpixel = fov == 70 && quality >= 256 && nearDetail == 64;
            if (triangles!=(subpixel?0:8192)) throw new AssertionError("Transparent subdivision/zoom/near visibility");
            checks++;
            if (triangles==0) continue;
            float[] result=new float[triangles*3*9];
            glGetNamedBufferSubData(output,0,result);
            int[] indices=fullIndices(4096);
            for (int i=0;i<indices.length;i++) {
                int id=indices[i],quad=id/4,corner=id%4;
                float x=(quad%64+(corner==1||corner==2?1:0))/64f;
                float y=(quad/64+(corner>=2?1:0))/64f;
                var expected=m.transform(new org.joml.Vector4f(x,y,0,1));
                check(result[i*9],expected.x); check(result[i*9+1],expected.y);
                check(result[i*9+4],220/255f); check(result[i*9+5],70/255f);
                check(result[i*9+6],35/255f); check(result[i*9+7],96/255f);
                if (Float.floatToRawIntBits(result[i*9+8])!=73) throw new AssertionError("Material ID changed");
                checks+=7;
            }
        }
        mesh.free(); glDeleteQueries(query); glDeleteBuffers(output); glDeleteProgram(program); glDeleteShader(v);
        glDisable(GL_RASTERIZER_DISCARD);
        System.out.println("Passed " + checks + " GPU zoom/refinement/transparent-attribute checks");
    }

@Test
    void verifyVolumetricDraws() throws Exception {
        String vertex = resource("distant.vert")
                .replace("#version 460 core", "#version 460 core\n#define TRAIN_DEPTH_REPLAY")
                + "\nvec2 distantTaaShift() { return vec2(0.015625, -0.015625); }";
        int shader = compile(GL_VERTEX_SHADER,vertex), program = glCreateProgram();
        glAttachShader(program,shader);
        glTransformFeedbackVaryings(program,new String[]{"gl_Position","fLodClip","fColor","fCustomId"},GL_INTERLEAVED_ATTRIBS);
        glLinkProgram(program);
        if (glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
        glUseProgram(program);
        glEnable(GL_RASTERIZER_DISCARD);
        int output = glCreateBuffers(), query = glGenQueries(), checks = 0;
        var fixtures = DistantMeshFixtures.fixtures();
        fixtures.add(new DistantMeshFixtures.Fixture("PowerGrid wire", DistantPolylineLodTest.wireFixture()));
        float[] matrix = new float[16];
        for (var fixture : fixtures) {
            var original = fixture.data();
            int quads = original.remaining()/128;
            int farZ = fixture.name().contains("wire") ? -2048 : -512;
            var lod = fixture.name().contains("wire") ? DistantPolylineLod.build(original,quads) : DistantMeshLod.build(original,quads);
            var data = MemoryUtil.memAlloc(original.remaining());
            data.put(original.duplicate()).flip();
            var mesh = new DistantMesh(data,quads,lod);
            MemoryUtil.memFree(data);
            var bounds = DistantMeshFixtures.bounds(original);
            mesh.minX=bounds[0]; mesh.minY=bounds[1]; mesh.minZ=bounds[2];
            mesh.maxX=bounds[3]; mesh.maxY=bounds[4]; mesh.maxZ=bounds[5];
            glNamedBufferData(output,quads*6L*13*4,GL_STREAM_READ);
            glBindBufferBase(GL_TRANSFORM_FEEDBACK_BUFFER,0,output);
            var nativeLod = new me.cortex.voxy.client.core.rendering.NativeLodSelection();
            int previousLevel = 0;
            for (float quality : new float[]{28,64,123,256,512,768,1024}) {
                var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(70),16f/9,.1f,50000);
                var transform = new org.joml.Matrix4f(projection).translate(0,0,farZ);
                nativeLod.update(projection,projection,1920,1080,0,0,0,quality,256);
                glUniformMatrix4fv(0,false,transform.get(matrix)); glUniformMatrix4fv(8,false,transform.get(matrix));
                mesh.drawAt(nativeLod,0,0,farZ);
                int farLevel = mesh.lastDrawLevel();
                if (farLevel<previousLevel) throw new AssertionError("Lower quality refined dedicated mesh");
                if (quality==256 && farLevel==0) throw new AssertionError("Standard should coarsen "+fixture.name());
                previousLevel = farLevel;
                mesh.drawAt(nativeLod,0,0,-128);
                if (mesh.lastDrawLevel()!=0) throw new AssertionError("Handoff area must keep full detail");
                mesh.draw(farLevel,0);
                if (mesh.lastDrawLevel()!=farLevel) throw new AssertionError("Shared instance replay level changed");
                checks+=4;
            }
            for (int width : new int[]{1920,3840}) {
                int previousTriangles = 0;
                for (float fov : new float[]{70,30,7,1,.05f}) {
                    var projection = new org.joml.Matrix4f().perspective((float)Math.toRadians(fov),16f/9,.1f,50000);
                    var local = new org.joml.Matrix4f().translate(0,0,farZ);
                    var transform = new org.joml.Matrix4f(projection).mul(local);
                    nativeLod.update(projection,projection,width,width*9/16,0,0,0,256,256);
                    glUniformMatrix4fv(0,false,transform.get(matrix)); glUniformMatrix4fv(8,false,transform.get(matrix));
                    int terrainLevel = nativeLod.level(bounds[0],bounds[1],bounds[2]+farZ,bounds[3],bounds[4],bounds[5]+farZ);
                    int expectedLevel = 0;
                    while (terrainLevel>0 && expectedLevel<lod.grids.length && lod.grids[expectedLevel]<=(1<<terrainLevel)) expectedLevel++;
                    for (boolean moving : new boolean[]{false,true}) {

                        glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                        glBeginTransformFeedback(GL_TRIANGLES);
                        if (moving) mesh.drawModel(nativeLod,local,0,0,0); else mesh.drawAt(nativeLod,0,0,farZ);
                        glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                        int triangles = glGetQueryObjecti(query,GL_QUERY_RESULT);
                        int expectedTriangles = expectedLevel==0?quads*2:lod.indexCount(expectedLevel-1)/3;
                        if (triangles!=expectedTriangles || mesh.lastDrawLevel()!=expectedLevel) throw new AssertionError("Native grid draw mismatch: "+fixture.name());
                        if (triangles<previousTriangles) throw new AssertionError("Native zoom lost detail");
                        if (fov==.05f && triangles!=quads*2) throw new AssertionError("Native zoom failed to restore original");
                        previousTriangles = triangles;
                        checks+=3;
                    }
                    // 同一帧换实例或深度回放不能污染静态网格的缓存级别。
                    mesh.drawAt(nativeLod,0,0,-128);
                    if (mesh.lastDrawLevel()!=0) throw new AssertionError("Native handoff lost detail");
                    mesh.drawAt(nativeLod,0,0,farZ);
                    if (mesh.lastDrawLevel()!=expectedLevel) throw new AssertionError("Native instance cache lost level");
                    int evaluations = nativeLod.evaluations;
                    mesh.draw(0,0);
                    mesh.drawAt(nativeLod,0,0,farZ);
                    if (mesh.lastDrawLevel()!=expectedLevel || nativeLod.evaluations!=evaluations) throw new AssertionError("Native cached replay mismatch");
                    checks+=3;
                }
            }
            var identity = new org.joml.Matrix4f();
            glUniformMatrix4fv(0,false,identity.get(matrix)); glUniformMatrix4fv(8,false,identity.get(matrix));
            // 往返每个 VBO 偏移，并读取真正提交给颜色/深度 shader 的顶点。
            for (int pass = 0; pass < 2; pass++) for (int step = 0; step <= lod.grids.length; step++) {
                int level = pass==0 ? step : lod.grids.length-step;
                glBeginQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN,query);
                glBeginTransformFeedback(GL_TRIANGLES);
                mesh.draw(level,.5f);
                glEndTransformFeedback(); glEndQuery(GL_TRANSFORM_FEEDBACK_PRIMITIVES_WRITTEN);
                int count = level==0 ? quads*6 : lod.indexCount(level-1);
                if (glGetQueryObjecti(query,GL_QUERY_RESULT)!=count/3) throw new AssertionError("Coarse VBO count mismatch");
                float[] result = new float[count*13];
                glGetNamedBufferSubData(output,0,result);
                int[] indices = fullIndices(count/6);
                for (int i = 0; i < count; i++) {
                    int v = indices[i];
                    for (int axis = 0; axis < 3; axis++) {
                        float expected = level==0 ? original.getFloat(v*32+axis*4) : Float.intBitsToFloat(lod.vertices[level-1][v*8+axis]);
                        check(result[i*13+axis],expected);
                        check(result[i*13+4+axis],expected+(axis==0?.015625f:axis==1?-.015625f:0));
                        checks+=2;
                    }
                    int color = level==0 ? original.getInt(v*32+24) : lod.vertices[level-1][v*8+6];
                    for (int c = 0; c < 4; c++) check(result[i*13+8+c],((color>>>(c*8))&255)/255f);
                    int material = level==0 ? original.getInt(v*32+28) : lod.vertices[level-1][v*8+7];
                    if (Float.floatToRawIntBits(result[i*13+12])!=material) throw new AssertionError("Coarse material mismatch");
                    checks+=5;
                }
            }
            mesh.free();
            System.out.println("GPU coarse/full/zoom/depth replay: "+fixture.name());
        }
        if (glGetError()!=GL_NO_ERROR) throw new AssertionError("Volumetric OpenGL error");
        glDeleteQueries(query); glDeleteBuffers(output); glDeleteProgram(program); glDeleteShader(shader);
        glDisable(GL_RASTERIZER_DISCARD);
        System.out.println("Passed "+checks+" GPU volumetric VBO/attribute/depth checks");
    }

@Test
    void verifyShaderVariants() throws Exception {
        String vertex = resource("distant.vert");
        String fragment = resource("distant.frag");
        String stub = "\nlayout(location=0) out vec4 resultColour;\n"
                + "void voxy_emitFragment(VoxyFragmentParameters p) { resultColour = p.sampledColour; }";
        for (String defines : new String[]{"", "UNIFORM_LIGHT", "TRANSLUCENT", "COPYCAT_OCCLUSION",
                "COPYCAT_OCCLUSION TRANSLUCENT", "PATCHED_SHADER UNIFORM_LIGHT",
                "PATCHED_SHADER TRANSLUCENT", "PATCHED_SHADER COPYCAT_OCCLUSION TRANSLUCENT",
                "TRAIN_DEPTH_REPLAY", "PATCHED_SHADER LASER TRANSLUCENT"}) {
            String header = "#version 460 core\n";
            for (String define : defines.split(" ")) if (!define.isEmpty()) header += "#define " + define + "\n";
            String vs = vertex.replace("#version 460 core", header)
                    + "\nvec2 distantTaaShift() { return vec2(0.0); }";
            String fs = defines.contains("TRAIN_DEPTH_REPLAY") ? resource("distant_depth.frag")
                    : defines.contains("LASER") ? resource("distant_laser.frag") : fragment;
            fs = fs.replace("#version 460 core", header);
            if (defines.contains("PATCHED_SHADER")) fs += stub;
            int v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
            int p = glCreateProgram();
            glAttachShader(p,v); glAttachShader(p,f); glLinkProgram(p);
            if (glGetProgrami(p,GL_LINK_STATUS)==0) throw new AssertionError(defines + ": " + glGetProgramInfoLog(p));
            glDeleteProgram(p); glDeleteShader(v); glDeleteShader(f);
        }
        System.out.println("Linked 10 distant shader variants (stub shader-pack output)");
    }

private static int[] fullIndices(int quads) {
        int[] result=new int[quads*6];
        for(int i=0;i<quads;i++) {
            int p=i*6,v=i*4;
            result[p]=v;result[p+1]=v+1;result[p+2]=v+2;
            result[p+3]=v+2;result[p+4]=v+3;result[p+5]=v;
        }
        return result;
    }

private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source); glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }

private static void check(float actual,float expected) {
        if (!Float.isFinite(actual) || Math.abs(actual-expected)>1e-6) throw new AssertionError(actual + " != " + expected);
    }

}
