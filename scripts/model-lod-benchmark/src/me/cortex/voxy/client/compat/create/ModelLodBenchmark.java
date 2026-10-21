package me.cortex.voxy.client.compat.create;

import com.google.gson.GsonBuilder;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.rendering.NativeLodSelection;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL45C.*;

/** Opt-in benchmark source. Never included by the normal build. */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class ModelLodBenchmark {
    private static final int WIDTH = 1920, HEIGHT = 1080, INSTANCES = 512;
    private static final int WARMUP = 120, SETTLE = 30, PATH = 120, BLOCKS = 4;
    private static final List<Fixture> fixtures = new ArrayList<>();
    private static final List<Map<String, Object>> bakeRows = new ArrayList<>();
    private static final List<Sample> samples = new ArrayList<>();
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static final ArrayDeque<Integer> queries = new ArrayDeque<>();
    private static final NativeLodSelection selection = new NativeLodSelection();
    private static final Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(70), 16f / 9, .1f, 50000);
    private static final Matrix4f model = new Matrix4f(), transform = new Matrix4f();
    private static int worldTicks, ticks, frame, framebuffer, depth;
    private static final int[] colors = new int[8];
    private static boolean ready, drew, finished, previewFull, previewLod;
    private static long frameStart;
    private static Sample current;
    private static String pipelineName;
    private static Throwable failure;
    private static boolean shaderVerified;

    private record Fixture(String name, DistantMesh full, DistantMesh lod) {}
    private record Pending(int query, Sample sample) {}
    private static final class Sample {
        String mode;
        int block, pathIndex;
        long submittedTriangles, fullDetailTriangles, coarseDraws, detailedDraws;
        long submitNs, gpuNs, frameNs;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        var mc = Minecraft.getInstance();
        if (++ticks > 12000 && !finished) throw new AssertionError("Model LOD benchmark timeout");
        if (mc.level == null || mc.player == null || ready || finished) return;
        if (++worldTicks != 100) return;
        var system = ((IGetVoxyRenderSystem)mc.levelRenderer).voxy$getRenderSystem();
        check(system != null && system.isIrisPipeline() == Boolean.getBoolean("voxy.benchmarkShaders"), "pipeline");
        // Freeze time/weather in the disposable copy; no source world is modified.
        mc.getSingleplayerServer().execute(() -> {
            var level = mc.getSingleplayerServer().overworld();
            level.setDayTime(6000);
            level.setWeatherParameters(1000000, 0, false, false);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        });
        VoxyConfig.CONFIG.subDivisionSize = 256;
        captureFixtures();
        makeFramebuffer();
        for (int i = 0; i < 32; i++) queries.add(glGenQueries());
        LodPipelineHooks.register(ModelLodBenchmark::render);
        ready = true;
        System.out.println("MODEL_LOD_BENCH ready: 512 instances, full/lod/lod/full blocks, 1920x1080");
    }

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) throws Exception {
        if (!ready || finished) return;
        drew = false; current = null;
        collect();
        if (frame >= WARMUP + BLOCKS * (SETTLE + PATH)) {
            if (pending.isEmpty()) finish();
            return;
        }
        frameStart = System.nanoTime();
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        // LodPipelineHooks catches renderer errors. Propagate outside that guard so
        // an invalid benchmark fails the client task instead of continuing silently.
        if (failure != null) throw new AssertionError("Model LOD benchmark failed", failure);
        if (!ready || finished || !drew) return;
        if (current != null) {
            current.frameNs = System.nanoTime() - frameStart;
            samples.add(current);
        }
        frame++;
        if (frame % 150 == 0) System.out.println("MODEL_LOD_BENCH frame=" + frame + " samples=" + samples.size());
    }

    private static void render(AbstractRenderPipeline pipeline, Viewport<?> view, int ignoredDepthFunc) {
        if (!ready || finished || drew || !view.isMainViewport || frame >= WARMUP + BLOCKS * (SETTLE + PATH)) return;
        drew = true;
        pipelineName = pipeline.getClass().getSimpleName();
        int step = frame - WARMUP;
        int block = step < 0 ? -1 : step / (SETTLE + PATH);
        int pathIndex = step < 0 ? frame % PATH : Math.max(0, step % (SETTLE + PATH) - SETTLE);
        boolean adaptive = step < 0 ? (frame & 1) != 0 : block == 1 || block == 2;
        boolean measured = step >= 0 && step % (SETTLE + PATH) >= SETTLE;
        if (measured) {
            current = new Sample(); current.mode = adaptive ? "lod" : "full";
            current.block = block; current.pathIndex = pathIndex;
        }
        int savedFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int savedReadFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        boolean stencil = glIsEnabled(GL_STENCIL_TEST);
        int[] savedViewport = new int[4]; glGetIntegerv(GL_VIEWPORT, savedViewport);
        try {
            pipeline.bindUniforms();
            var shader = DistantShaders.forPipeline(pipeline, false); shader.bind();
            if (!shaderVerified) {
                if (Boolean.getBoolean("voxy.benchmarkShaders")) {
                    var patched = DistantShaders.class.getDeclaredField("patchedVertexLight");
                    patched.setAccessible(true);
                    check(patched.get(null) == shader, "shader pack silently fell back to plain shader");
                }
                shaderVerified = true;
            }
            DistantShaders.bindTextures();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer); glViewport(0, 0, WIDTH, HEIGHT);
            glEnable(GL_DEPTH_TEST); glDepthFunc(GL_LEQUAL); glDepthMask(true);
            glDisable(GL_STENCIL_TEST); glDisable(GL_BLEND); glDisable(GL_CULL_FACE);
            // Color attachment clear does not modify global clear-color state.
            for (int i = 0; i < colors.length; i++) glClearBufferfv(GL_COLOR, i, new float[]{0, 0, 0, 0});
            glClearBufferfv(GL_DEPTH, 0, new float[]{1});
            double phase = pathIndex * Math.PI * 2 / PATH;
            double cx = Math.sin(phase) * 48, cy = 72 + Math.sin(phase * 2) * 8, cz = Math.cos(phase) * 96;
            selection.update(projection, projection, WIDTH, HEIGHT, cx, cy, cz, 256, 96);
            var before = DistantMesh.frameStatistics();
            int query = 0;
            if (measured) {
                check(!queries.isEmpty(), "GPU query backlog exceeded 32 frames");
                query = queries.removeFirst(); glBeginQuery(GL_TIME_ELAPSED, query);
            }
            long start = System.nanoTime();
            for (int i = 0; i < INSTANCES; i++) {
                var fixture = fixtures.get(i % fixtures.size());
                var mesh = adaptive ? fixture.lod : fixture.full;
                model.identity().translate((float)((i % 16 - 7.5) * 24 - cx), (float)(64 - cy), (float)(-512 - (i / 16) * 32 - cz))
                        .rotateY((i % 8) * (float)Math.PI / 4);
                DistantShaders.uploadTransform(transform.set(projection).mul(model));
                if (adaptive) mesh.drawModel(selection, model, cx, cy, cz); else mesh.draw();
            }
            long submitNs = System.nanoTime() - start;
            if (measured) {
                glEndQuery(GL_TIME_ELAPSED);
                var after = DistantMesh.frameStatistics();
                current.submitNs = submitNs;
                current.submittedTriangles = after.submittedTriangles() - before.submittedTriangles();
                current.fullDetailTriangles = after.fullDetailTriangles() - before.fullDetailTriangles();
                current.coarseDraws = after.coarseDraws() - before.coarseDraws();
                current.detailedDraws = after.detailedDraws() - before.detailedDraws();
                check(current.coarseDraws + current.detailedDraws == INSTANCES, "draw count");
                pending.add(new Pending(query, current));
            }
            if (!measured && (adaptive ? !previewLod : !previewFull)) {
                preview(adaptive ? "lod" : "full");
                if (adaptive) previewLod = true; else previewFull = true;
            }
            check(glGetError() == GL_NO_ERROR, "benchmark OpenGL error");
        } catch (Throwable error) {
            failure = error;
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, savedFbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, savedReadFbo);
            glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            if (stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
        }
    }

    private static void collect() {
        while (!pending.isEmpty()) {
            var item = pending.peek();
            if (glGetQueryObjecti(item.query, GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) break;
            pending.remove(); item.sample.gpuNs = glGetQueryObjectui64(item.query, GL_QUERY_RESULT);
            queries.add(item.query);
        }
    }

    private static void captureFixtures() throws Exception {
        var blocks = new ArrayList<ShapeBlock>();
        var casing = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:andesite_casing")).defaultBlockState();
        check(!casing.isAir(), "Create assets unavailable");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) {
            if (y == 0 || y == 3 || x == 0 || x == 3) blocks.add(new ShapeBlock((byte)x, (byte)y, (byte)z, casing));
        }
        long start = System.nanoTime();
        var carriage = CarriageMeshBaker.bake(blocks);
        check(carriage != null, "carriage bake"); capture("carriage", carriage.mesh, System.nanoTime() - start);
        carriage.mesh.free();
        for (var size : List.of(BogeySizes.SMALL, BogeySizes.LARGE)) {
            start = System.nanoTime();
            var mesh = DistantBogeyMeshes.getOrCapture(new ShapeBogey(ResourceLocation.parse("create:standard"), size.id(), .5f, new CompoundTag()));
            check(mesh != null, "bogey bake"); capture("bogey-" + size.id(), mesh, System.nanoTime() - start);
        }
        var bucket = new KineticSnapshots.Bucket();
        var shaft = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:shaft")).defaultBlockState();
        var cog = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:large_cogwheel")).defaultBlockState();
        for (int z = 0; z < 12; z++) bucket.geoms.put(new BlockPos(0, 64, 0).offset(0, 0, z), new KineticSnapshots.Snap(z % 2 == 0 ? shaft : cog,
                Direction.Axis.Y, z * .13f, 15, 3, true, null, null, 0, false, null, null, null, false));
        var rebake = KineticSnapshots.class.getDeclaredMethod("rebake", KineticSnapshots.Bucket.class); rebake.setAccessible(true);
        start = System.nanoTime(); rebake.invoke(null, bucket);
        check(bucket.mesh != null, "kinetic bake"); capture("kinetic-section", bucket.mesh, System.nanoTime() - start); bucket.mesh.free();
    }

    private static void capture(String name, DistantMesh sourceMesh, long sourceCaptureNs) throws Exception {
        var field = DistantMesh.class.getDeclaredField("vbo"); field.setAccessible(true);
        var source = MemoryUtil.memAlloc(sourceMesh.quadCount() * 128);
        DistantMesh full = null, adaptive = null;
        try {
            glGetNamedBufferSubData(field.getInt(sourceMesh), 0, source);
            long[] fullBake = new long[20], lodBake = new long[20];
            DistantMeshLod levels = DistantMeshLod.EMPTY;
            // Copy and simplify identical captured vertex streams. Capturing Create models
            // and rendering are measured separately from this mesh-preparation stage.
            for (int i = -10; i < 20; i++) {
                for (int j = 0; j < 2; j++) {
                    boolean lod = ((i + j) & 1) != 0;
                    long start = System.nanoTime();
                    var copy = MemoryUtil.memAlloc(source.remaining());
                    try {
                        copy.put(source.duplicate()).flip();
                        var result = lod ? DistantMeshLod.build(copy, sourceMesh.quadCount()) : DistantMeshLod.EMPTY;
                        long elapsed = System.nanoTime() - start;
                        if (i >= 0) { if (lod) lodBake[i] = elapsed; else fullBake[i] = elapsed; }
                        if (lod) levels = result;
                    } finally { MemoryUtil.memFree(copy); }
                }
            }
            check(levels.grids.length > 0, "fixture must exercise LOD");
            full = new DistantMesh(source, sourceMesh.quadCount(), DistantMeshLod.EMPTY);
            adaptive = new DistantMesh(source, sourceMesh.quadCount(), levels);
            for (var mesh : List.of(full, adaptive)) {
                mesh.minX = sourceMesh.minX; mesh.minY = sourceMesh.minY; mesh.minZ = sourceMesh.minZ;
                mesh.maxX = sourceMesh.maxX; mesh.maxY = sourceMesh.maxY; mesh.maxZ = sourceMesh.maxZ;
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("name", name); row.put("sourceCaptureNs", sourceCaptureNs);
            row.put("fullPrepareNs", fullBake); row.put("lodPrepareNs", lodBake);
            row.put("fullCpuPayloadBytes", source.remaining()); row.put("lodCpuPayloadBytes", source.remaining() + levels.byteSize());
            row.put("fullGpuBytes", full.gpuByteSize()); row.put("lodGpuBytes", adaptive.gpuByteSize());
            row.put("fullTriangles", sourceMesh.quadCount() * 2);
            int[] triangles = new int[levels.grids.length];
            for (int i = 0; i < triangles.length; i++) triangles[i] = levels.indexCount(i) / 3;
            row.put("lodTriangles", triangles);
            bakeRows.add(row); fixtures.add(new Fixture(name, full, adaptive));
            System.out.println("MODEL_LOD_BENCH captured " + name + " full=" + sourceMesh.quadCount() * 2);
        } catch (Exception | Error failure) {
            if (full != null) full.free(); if (adaptive != null) adaptive.free(); throw failure;
        } finally { MemoryUtil.memFree(source); }
    }

    private static void makeFramebuffer() {
        framebuffer = glCreateFramebuffers();
        int[] drawBuffers = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = glCreateTextures(GL_TEXTURE_2D); glTextureStorage2D(colors[i], 1, GL_RGBA16F, WIDTH, HEIGHT);
            glNamedFramebufferTexture(framebuffer, GL_COLOR_ATTACHMENT0 + i, colors[i], 0);
            drawBuffers[i] = GL_COLOR_ATTACHMENT0 + i;
        }
        depth = glCreateTextures(GL_TEXTURE_2D); glTextureStorage2D(depth, 1, GL_DEPTH_COMPONENT32F, WIDTH, HEIGHT);
        glNamedFramebufferTexture(framebuffer, GL_DEPTH_ATTACHMENT, depth, 0);
        glNamedFramebufferDrawBuffers(framebuffer, drawBuffers);
        check(glCheckNamedFramebufferStatus(framebuffer, GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE, "benchmark FBO");
    }

    private static void preview(String mode) throws Exception {
        var data = MemoryUtil.memAlloc(WIDTH * HEIGHT * 4);
        try {
            glGetTextureImage(colors[0], 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
            var image = new java.awt.image.BufferedImage(WIDTH / 4, HEIGHT / 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
            int visible = 0;
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                int offset = (y * WIDTH + x) * 4;
                int rgb = (data.get(offset) & 255) << 16 | (data.get(offset + 1) & 255) << 8 | data.get(offset + 2) & 255;
                if (rgb != 0) visible++;
                if ((x & 3) == 0 && (y & 3) == 0) image.setRGB(x / 4, (HEIGHT - 1 - y) / 4, rgb);
            }
            check(visible > 100, "empty benchmark image: " + mode);
            javax.imageio.ImageIO.write(image, "png", Path.of("preview-" + mode + ".png").toFile());
            System.out.println("MODEL_LOD_BENCH preview " + mode + " visiblePixels=" + visible);
        } finally { MemoryUtil.memFree(data); }
    }

    private static void finish() throws Exception {
        check(samples.size() == BLOCKS * PATH, "incomplete frame sample set");
        check(previewFull && previewLod && shaderVerified, "missing preview or shader validation");
        check(samples.stream().allMatch(s -> s.submitNs > 0 && s.gpuNs > 0 && s.frameNs > 0), "missing timing samples");
        var report = new LinkedHashMap<String, Object>();
        report.put("pipeline", pipelineName); report.put("renderer", glGetString(GL_RENDERER)); report.put("glVersion", glGetString(GL_VERSION));
        report.put("resolution", new int[]{WIDTH, HEIGHT}); report.put("instances", INSTANCES);
        report.put("pathFrames", PATH); report.put("warmupFrames", WARMUP); report.put("settleFramesPerBlock", SETTLE);
        report.put("cameraPath", "far-grid-v1"); report.put("fovDegrees", 70);
        report.put("subDivisionSize", 256); report.put("fullDetailDistance", 96);
        report.put("shaderVerified", shaderVerified);
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("blockOrder", List.of("full", "lod", "lod", "full"));
        report.put("bakes", bakeRows); report.put("frames", samples);
        report.put("scope", "Same captured Create geometry, full vs adaptive mesh policy in one binary; actual shader pipeline renders an offscreen scene during normal Minecraft frames. GPU timers cover scene draws. frameNs is RenderFrameEvent Pre/Post wall time including the scene and normal game rendering, excluding presentation/vsync. CPU payload excludes Java object overhead and is freed after upload; shared index/FBO/texture memory is common to both modes.");
        Files.writeString(Path.of("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        for (var fixture : fixtures) { fixture.full.free(); fixture.lod.free(); }
        glDeleteTextures(colors); glDeleteTextures(depth); glDeleteFramebuffers(framebuffer);
        while (!queries.isEmpty()) glDeleteQueries(queries.removeFirst());
        finished = true; ready = false;
        System.out.println("MODEL_LOD_BENCH PASS frames=" + samples.size() + " pipeline=" + pipelineName);
        Minecraft.getInstance().stop();
    }

    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
