package me.cortex.voxy.audit;

import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.*;
import me.cortex.voxy.client.core.rendering.WorldRenderContext;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport;
import me.cortex.voxy.common.voxelization.*;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import net.mehvahdjukaar.moonlight.core.client.DummyCamera;
import net.mehvahdjukaar.vista.client.renderer.VistaLevelRenderer;
import net.mehvahdjukaar.vista.client.textures.LiveFeedTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.*;
import java.util.*;
import static org.lwjgl.opengl.GL45C.*;

/** Uses Vista's actual scene renderer and shader pipeline, with LOD-only water as a visible fixture. */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class VistaWaterSmoke {
    private static int ticks, frames, captures;
    private static int waterWarmupUntil;
    private static boolean ready, done, moved;
    private static LiveFeedTexture[] feeds;
    private static Object failedOwner, failedData;
    private static String originalPatch;
    private static final List<Map<String, Object>> rows = new ArrayList<>();

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (done || mc.level == null || mc.player == null) return;
        if (++ticks > 3600) throw new AssertionError("Vista water fixture timed out");
        if (!moved) {
            moved = true;
            org.lwjgl.glfw.GLFW.glfwHideWindow(mc.getWindow().getWindow());
            mc.getSingleplayerServer().execute(() -> {
                var server = mc.getSingleplayerServer();
                var player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SPECTATOR);
                player.teleportTo(0, 100, 0);
                player.setYRot(180); player.setXRot(12);
                server.overworld().setDayTime(6000);
                server.overworld().setWeatherParameters(1000000, 0, false, false);
            });
        }
        if (ready || ticks < 160) return;
        var vrs = ((IGetVoxyRenderSystem) mc.levelRenderer).voxy$getRenderSystem();
        if (vrs == null) return;
        var world = vrs.getEngine();
        int water = world.getMapper().getIdForBlockState(Blocks.WATER.defaultBlockState());
        int sand = world.getMapper().getIdForBlockState(Blocks.SAND.defaultBlockState());
        int stone = world.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
        for (int cx = -8; cx < 8; cx++) for (int cz = -56; cz < -8; cz++) {
            var section = VoxelizedSection.createEmpty().setPosition(cx, 4, cz).uniformAir(Mapper.airWithLight(15));
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int material = y < (cx < 0 ? 8 : 2) ? sand : y < 12 ? water : cx < -5 && cz < -16 ? stone : 0;
                if (material == 0) continue;
                section.section[(y << 8) | (z << 4) | x] = Mapper.composeMappingId((byte) 15, material, 0);
                section.lvl0NonAirCount++;
            }
            WorldVoxilizedSectionMipper.mipSection(section, world.getMapper());
            WorldUpdater.insertUpdate(world, section);
        }
        feeds = new LiveFeedTexture[]{
                new LiveFeedTexture(ResourceLocation.fromNamespaceAndPath("voxy", "water_probe_a"), 384, 216, UUID.randomUUID()),
                new LiveFeedTexture(ResourceLocation.fromNamespaceAndPath("voxy", "water_probe_b"), 256, 144, UUID.randomUUID())};
        ready = true;
        waterWarmupUntil = ticks + 400;
        System.out.println("VISTA_WATER fixture ready");
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) throws Exception {
        if (!ready || done || ++frames % 5 != 0 || ticks < 260) return;
        var mc = Minecraft.getInstance();
        var vrs = ((IGetVoxyRenderSystem) mc.levelRenderer).voxy$getRenderSystem();
        if (vrs == null) return;
        AbstractRenderPipeline main = (AbstractRenderPipeline) field(vrs, "pipeline");
        int mainDepth = main.fb.getDepthTex().id;
        int mainWidth = main.fb.getDepthTex().getWidth(), mainHeight = main.fb.getDepthTex().getHeight();
        float[] before = new float[mainWidth * mainHeight];
        glGetTextureImage(mainDepth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, before);
        var mainMvp = new org.joml.Matrix4f(vrs.getViewport().MVP);
        var mainPrevious = new org.joml.Matrix4f(vrs.getViewport().history.previousViewProjection);
        var feed = feeds[captures % 2];
        final float yaw = 180 + (captures % 30 - 15) * .25f;
        boolean[] rendered = {false};
        VistaLevelRenderer.render(feed, feed, (camera, partialTick) -> {
            rendered[0] = true;
            var dummy = (DummyCamera) camera;
            dummy.setup(mc.level, mc.player, false, false, partialTick);
            dummy.setPosition(0, 100, -96);
            dummy.setRotation(yaw, 12);
        }, 60 + (captures % 20), true, null, null, 4);
        // Vista intentionally skips the first request after a dimension change.
        if (!rendered[0]) return;
        check(WorldRenderContext.current() == null, "render context leaked");
        check(mainDepth == main.fb.getDepthTex().id && mainWidth == main.fb.getDepthTex().getWidth(), "main depth resized");
        check(mainMvp.equals(vrs.getViewport().MVP), "main camera overwritten");
        check(mainPrevious.equals(vrs.getViewport().history.previousViewProjection), "main matrix history overwritten");
        float[] after = new float[before.length];
        glGetTextureImage(mainDepth, 0, GL_DEPTH_COMPONENT, GL_FLOAT, after);
        check(Arrays.equals(before, after), "feed changed main depth pixels");
        var passes = (Map<?, ?>) field(vrs, "secondaryPasses");
        int translucent = 0;
        boolean nativeShader = false;
        for (Object pass : passes.values()) {
            AbstractRenderPipeline pipeline = (AbstractRenderPipeline) field(pass, "pipeline");
            var views = (Map<?, ?>) field(pass, "views");
            Object view = views.get(feed.getRenderTarget());
            if (view == null) continue;
            var viewport = (MDICViewport) field(view, "viewport");
            int[] counts = new int[8];
            glGetNamedBufferSubData(viewport.drawCountCallBuffer.id, 0, counts);
            translucent = Math.max(translucent, counts[4]);
            check(!viewport.isMainViewport, "feed was mistaken for the main camera");
            nativeShader |= pipeline.usesShaderPack();
            if (pipeline instanceof IrisVoxyRenderPipeline iris) {
                check(iris.fb.getDepthTex() != null && iris.fbTranslucent.getDepthTex() != null, "missing feed depth samplers");
                check(iris.fb.getDepthTex().id != mainDepth && iris.fbTranslucent.getDepthTex().id != mainDepth, "feed sampled main depth");
                var data = (me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData) field(iris, "data");
                check(data.thePipeline == iris, "depth sampler owner mismatch");
            }
        }
        // Shader toggles rebuild the shared model/mesh renderer asynchronously.
        // Keep rendering and checking main-view isolation while waiting for real water draws.
        if (translucent == 0 && ticks < waterWarmupUntil) return;
        check(translucent > 0, "no LOD water commands after warmup");
        captures++;
        if (captures % 20 == 0) {
            System.out.println("VISTA_WATER capture=" + captures + " waterCommands=" + translucent + " native=" + nativeShader);
            preview(feed.getRenderTarget().getColorTextureId(), feed.getRenderTarget().width, feed.getRenderTarget().height, "feed-" + captures);
            preview(mc.getMainRenderTarget().getColorTextureId(), mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, "main-" + captures);
            rows.add(Map.of("capture", captures, "waterCommands", translucent, "nativeShader", nativeShader));
        }
        boolean shaders = Boolean.getBoolean("voxy.vistaWaterShaders");
        boolean expectedNative = shaders && !Boolean.getBoolean("voxy.vistaWaterIrisOff")
                && (captures <= 80 || captures > 90)
                && !(Boolean.getBoolean("voxy.vistaWaterFailPatch") && captures > 20 && captures <= 30);
        check(nativeShader == expectedNative, "expected shader pipeline=" + expectedNative + " at capture " + captures);
        if (captures % 20 == 0) check(translucent > 0, "no LOD water commands");
        if (Boolean.getBoolean("voxy.vistaWaterFailPatch") && captures == 20) {
            for (var entry : passes.entrySet()) {
                if (field(entry.getValue(), "pipeline") instanceof IrisVoxyRenderPipeline iris) {
                    failedOwner = entry.getKey();
                    failedData = field(iris, "data");
                    break;
                }
            }
            check(failedData != null, "fault injection requires a native feed pipeline");
            originalPatch = (String) field(failedData, "opaquePatch");
            vrs.releaseIrisPass(failedOwner);
            setField(failedData, "opaquePatch", "\n#error Vista_water_failure_probe\n");
        }
        if (failedData != null && captures == 21) {
            check(field(failedData, "thePipeline") == null, "failed shader pipeline retained resources");
            setField(failedData, "opaquePatch", originalPatch);
        }
        if (captures == 30) {
            if (failedOwner != null) vrs.releaseIrisPass(failedOwner);
            for (int i = 0; i < feeds.length; i++) {
                var target = feeds[i].getRenderTarget();
                feeds[i].close();
                checkNoView(vrs, target);
                feeds[i] = new LiveFeedTexture(ResourceLocation.fromNamespaceAndPath("voxy", "water_probe_resized_" + i),
                        mc.getMainRenderTarget().width, mc.getMainRenderTarget().height, UUID.randomUUID());
            }
        }
        if (captures == 40) {
            for (var texture : feeds) {
                var target = texture.getRenderTarget();
                target.resize(target.width, target.height, Minecraft.ON_OSX);
                checkNoView(vrs, target);
            }
        }
        if (captures == 50) {
            var target = feeds[0].getRenderTarget();
            feeds[0].close();
            checkNoView(vrs, target);
            feeds[0] = new LiveFeedTexture(ResourceLocation.fromNamespaceAndPath("voxy", "water_probe_reopened"),
                    384, 216, UUID.randomUUID());
        }
        if (shaders && captures == 60) {
            net.irisshaders.iris.Iris.reload();
            check(passes.isEmpty(), "shader reload retained secondary pipelines");
            waterWarmupUntil = ticks + 400;
        }
        if (shaders && (captures == 80 || captures == 90)) {
            net.irisshaders.iris.api.v0.IrisApi.getInstance().getConfig().setShadersEnabledAndApply(captures == 90);
            check(passes.isEmpty(), "shader toggle retained secondary pipelines");
            waterWarmupUntil = ticks + 400;
        }
        if (captures < 120) return;
        check(glGetError() == GL_NO_ERROR, "OpenGL error");
        Files.writeString(Path.of("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "state", "passed", "captures", captures, "rows", rows, "renderer", glGetString(GL_RENDERER))));
        done = true;
        for (var texture : feeds) {
            var target = texture.getRenderTarget();
            texture.close();
            checkNoView(vrs, target);
        }
        var scenarios = new ArrayList<>(List.of("coast", "shallow-water", "deep-water", "alternate-feeds",
                "rotate-zoom", "same-size-targets", "resize-recreate", "close-reopen"));
        if (shaders) scenarios.add("shader-reload-toggle");
        if (Boolean.getBoolean("voxy.vistaWaterFailPatch")) scenarios.add("patch-failure-fallback");
        Files.writeString(Path.of("verification.json"), new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "state", "passed", "shaders", shaders, "irisOff", Boolean.getBoolean("voxy.vistaWaterIrisOff"),
                "captures", captures, "scenarios", scenarios, "vista", "5.4.1",
                "iris", shaders ? "1.8.14-beta.1" : "absent", "shaderPack", shaders ? "ComplementaryReimagined_r5.8.1" : "none")));
        System.out.println("VISTA_WATER PASS");
        mc.stop();
    }

    private static void checkNoView(VoxyRenderSystem renderer, com.mojang.blaze3d.pipeline.RenderTarget target) throws Exception {
        for (var pass : ((Map<?, ?>) field(renderer, "secondaryPasses")).values())
            check(!((Map<?, ?>) field(pass, "views")).containsKey(target), "destroyed target retained viewport resources");
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void setField(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void preview(int texture, int width, int height, String name) throws Exception {
        var bytes = MemoryUtil.memAlloc(width * height * 4);
        try {
            glGetTextureImage(texture, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                // Vista flips the camera projection for texture presentation.
                image.setRGB(x, name.startsWith("feed-") ? y : height - 1 - y,
                        (bytes.get(i) & 255) << 16 | (bytes.get(i + 1) & 255) << 8 | bytes.get(i + 2) & 255);
            }
            javax.imageio.ImageIO.write(image, "png", Path.of(name + ".png").toFile());
        } finally { MemoryUtil.memFree(bytes); }
    }
}
