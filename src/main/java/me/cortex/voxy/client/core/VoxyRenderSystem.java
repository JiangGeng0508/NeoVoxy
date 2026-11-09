package me.cortex.voxy.client.core;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.FrameProfiler;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.compat.create.DistantShaders;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.WorldRenderContext;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.IUsesMeshlets;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.PerfStats;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyProfile;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30.glGetIntegeri;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;

/**
 * 客户端 LOD 渲染系统。
 *
 * 初始化阶段组装模型、层次遍历、区块遮挡和绘制后端；每帧阶段只负责准备视口、执行
 * 管线并恢复 Minecraft 的 GL 状态。资源释放顺序与创建顺序相反。
 */
public class VoxyRenderSystem {
    // Hot-reloadable render pressure tables. Index is VoxyConfig.renderPressure:
    // 0 = maximum FPS / slowest LOD catch-up, 4 = fastest LOD catch-up / highest frame pressure.
    private static final long[] MODEL_BAKE_BUDGET_LOW_FPS = {75_000L, 150_000L, 250_000L, 450_000L, 900_000L};
    private static final long[] MODEL_BAKE_BUDGET_BUSY = {150_000L, 300_000L, 500_000L, 750_000L, 1_200_000L};
    private static final long[] MODEL_BAKE_BUDGET_IDLE = {300_000L, 550_000L, 900_000L, 1_350_000L, 2_000_000L};
    private static final int[] TOP_LEVEL_NODE_PROCESS_RATE = {4, 8, 12, 24, 40};

    private final WorldEngine worldIn;


    private final ModelBakerySubsystem modelService;
    private final RenderGenerationService renderGen;
    private final IGeometryData geometryData;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;


    private final RenderDistanceTracker renderDistanceTracker;
    public final ChunkBoundRenderer chunkBoundRenderer;

    private final ViewportSelector<?> viewportSelector;

    private final AbstractRenderPipeline pipeline;
    private final RenderProperties properties;

    private final int[] savedBufferBindings = new int[10];
    private final int[] viewportDimensions = new int[4];
    private final Matrix4f projectionScratch = new Matrix4f();
    private final Matrix4f modifiedProjectionScratch = new Matrix4f();
    private Viewport<?> pendingUnpatchedIrisViewport;
    private static final Object PLAIN_SECONDARY = new Object();
    private final Map<Object, SecondaryPass> secondaryPasses = new IdentityHashMap<>();
    private final Set<Object> failedSecondaryPasses = Collections.newSetFromMap(new IdentityHashMap<>());

    private static final class SecondaryView {
        final Viewport<?> viewport;
        final ChunkBoundRenderer bounds;
        int depth = -1, colour = -1;
        SecondaryView(AbstractRenderPipeline pipeline, AbstractSectionRenderer<?, ?> renderer) {
            this.viewport = renderer.createViewport();
            try {
                this.bounds = new ChunkBoundRenderer(pipeline);
            } catch (RuntimeException error) {
                this.viewport.delete();
                throw error;
            }
        }
        void free() { this.bounds.free(); this.viewport.delete(); }
    }

    private final class SecondaryPass {
        final AbstractRenderPipeline pipeline;
        final AbstractSectionRenderer<?, ?> renderer;
        final Map<RenderTarget, SecondaryView> views = new IdentityHashMap<>();
        SecondaryPass(IrisVoxyRenderPipelineData data) {
            this.pipeline = data == null
                    ? new NormalRenderPipeline(properties, nodeManager, nodeCleaner, traversal, () -> false)
                    : new IrisVoxyRenderPipeline(properties, data, nodeManager, nodeCleaner, traversal, () -> false, true);
            try {
                this.renderer = getRenderBackendFactory().create(this.pipeline, modelService.getStore(), geometryData);
                this.pipeline.setSectionRenderer(this.renderer);
                traversal.lateStageCompile(this.pipeline);
            } catch (RuntimeException error) {
                traversal.releasePipeline(this.pipeline);
                this.pipeline.free();
                throw error;
            }
        }
        void free() {
            this.views.values().forEach(SecondaryView::free);
            this.views.clear();
            traversal.releasePipeline(this.pipeline);
            this.pipeline.free();
        }
    }

    public void beginIrisPass(Object owner, IrisVoxyRenderPipelineData data) {
        var context = WorldRenderContext.current();
        if (context == null) return;
        context.irisRendering = true;
        if (context.irisOwner != owner) {
            context.viewport = null;
            context.pipeline = null;
            context.chunkBounds = null;
        }
        context.irisOwner = owner;
        context.irisData = data;
        this.prepareContext(context);
    }

    public void releaseIrisPass(Object owner) {
        var pass = this.secondaryPasses.remove(owner);
        if (pass != null) pass.free();
        this.failedSecondaryPasses.remove(owner);
    }

    public void releaseSecondaryPasses() {
        this.secondaryPasses.values().forEach(SecondaryPass::free);
        this.secondaryPasses.clear();
        this.failedSecondaryPasses.clear();
    }

    /** Called before a RenderTarget destroys its attachments, including same-size rebuilds. */
    public void releaseRenderTarget(RenderTarget target) {
        for (var pass : this.secondaryPasses.values()) {
            var view = pass.views.remove(target);
            if (view != null) view.free();
        }
    }

    private SecondaryView prepareSecondaryView(WorldRenderContext context) {
        Object key = context.irisOwner == null ? PLAIN_SECONDARY : context.irisOwner;
        var pass = this.secondaryPasses.get(key);
        try {
            if (pass == null) {
                pass = new SecondaryPass(this.failedSecondaryPasses.contains(key) ? null : context.irisData);
                this.secondaryPasses.put(key, pass);
            }
            var view = pass.views.get(context.target);
            if (view == null) {
                view = new SecondaryView(pass.pipeline, pass.renderer);
                pass.views.put(context.target, view);
            }
            context.pipeline = pass.pipeline;
            return view;
        } catch (RuntimeException error) {
            if (context.irisData == null || !this.failedSecondaryPasses.add(key)) throw error;
            this.secondaryPasses.remove(key);
            if (pass != null) pass.free();
            Logger.error("Vista LOD shader pipeline failed; using plain water for this feed until shader reload", error);
            return this.prepareSecondaryView(context);
        }
    }

    private void prepareContext(WorldRenderContext context) {
        if (context.viewport != null) return;
        boolean resetHistory = false;
        int width, height;
        if (!context.secondary) {
            context.pipeline = this.pipeline;
            context.viewport = this.viewportSelector.getViewport();
            context.chunkBounds = this.chunkBoundRenderer;
            int[] size = this.mainViewportSize();
            if (size == null) return;
            width = size[0]; height = size[1];
        } else {
            var view = this.prepareSecondaryView(context);
            context.viewport = view.viewport;
            context.chunkBounds = view.bounds;
            width = context.target.width; height = context.target.height;
            float[] scale = context.pipeline.getRenderScalingFactor();
            if (scale != null) { width = (int) (width * scale[0]); height = (int) (height * scale[1]); }
            resetHistory = view.depth != context.target.getDepthTextureId() || view.colour != context.target.getColorTextureId();
            view.depth = context.target.getDepthTextureId(); view.colour = context.target.getColorTextureId();
        }
        if (width <= 0 || height <= 0) throw new IllegalStateException("Empty world render target");
        resetHistory |= context.viewport.width != width || context.viewport.height != height;
        if (resetHistory) context.viewport.invalidateChunkMask();
        double cameraX = context.x, cameraY = context.y;
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int) Math.floor(cameraX) >> 4) + 512) >> 10;
            cameraX -= sector << 14;
            cameraY += (16 + (256 - 32 - sector * 30)) * 16;
        }
        this.configureViewport(context.viewport, new ChunkRenderMatrices(context.projection, context.modelView),
                this.computeProjectionMat(this.properties, context.projection, this.currentFarPlane()),
                cameraX, cameraY, context.z, width, height);
        context.viewport.history.update(context.viewport.projection, context.viewport.modelView, resetHistory);
    }

    public Viewport<?> getActiveViewport() {
        var context = WorldRenderContext.current();
        if (context == null) return this.getViewport();
        this.prepareContext(context);
        return context.viewport;
    }

    public ChunkBoundRenderer getActiveChunkBoundRenderer() {
        var context = WorldRenderContext.current();
        if (context == null) return this.chunkBoundRenderer;
        this.prepareContext(context);
        return context.chunkBounds;
    }

    private AbstractRenderPipeline activePipeline() {
        var context = WorldRenderContext.current();
        return context != null && context.pipeline != null ? context.pipeline : this.pipeline;
    }



    public String getPipelineName() {
        return this.pipeline == null ? "none" : this.pipeline.getClass().getSimpleName();
    }

    /** Includes model waiters: generation must not grow a blocked downstream indefinitely. */
    public int getPendingMeshCount() { return this.renderGen.getTaskCount(); }
    public double getMeshResultPressure() { return this.renderGen.getResultMemoryStats().pressure(); }
    public double getNodeQueuePressure() { return this.nodeManager.getQueueStats().pressure(); }
    public AsyncNodeManager.QueueStats getNodeQueueStats() { return this.nodeManager.getQueueStats(); }
    public me.cortex.voxy.client.core.rendering.building.MeshResultBudget.Stats getMeshResultMemoryStats() {
        return this.renderGen.getResultMemoryStats();
    }

    private static AbstractSectionRenderer.Factory<?, ? extends IGeometryData> getRenderBackendFactory() {
        return MDICSectionRenderer.FACTORY;
    }

    public VoxyRenderSystem(WorldEngine world, ServiceManager sm) {
        // 先持有世界引用，再创建其他渲染资源，防止初始化超时期间世界被回收。
        world.acquireRef();
        Logger.info("Creating Voxy render system");

        if (Minecraft.getInstance().options.renderDistance().get() < 3) {
            String msg = "Voxy: Having a vanilla render distance of 2 can cause rare culling near the edge of your screen issues, please use 3 or more";
            Logger.warn(msg);
            Minecraft.getInstance().getChatListener().handleSystemMessage(Component.literal(msg), false);
        }

        // 进入管线前保存外部 SSBO 绑定，退出时完整恢复调用方状态。
        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            this.savedBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        try {
            // 等待 GPU 完成上一阶段，确保旧资源已经不再被使用。
            glFinish();
            glFinish();

            this.worldIn = world;

            this.properties = RenderProperties.getRenderProperties();
            var backendFactory = getRenderBackendFactory();
            {
                this.modelService = new ModelBakerySubsystem(world.getMapper());
                this.renderGen = new RenderGenerationService(world, this.modelService, sm, IUsesMeshlets.class.isAssignableFrom(backendFactory.clz()));

                this.geometryData = new BasicSectionGeometryData(1 << 20, RenderResourceReuse.getOrCreateGeometryBuffer());

                this.nodeManager = new AsyncNodeManager(1 << 21, this.geometryData, this.renderGen);
                this.nodeCleaner = new NodeCleaner(this.nodeManager);
                this.traversal = new HierarchicalOcclusionTraverser(this.nodeManager, this.nodeCleaner, this.renderGen);

                world.setDirtyCallback(this.nodeManager::worldEvent);

                Arrays.stream(world.getMapper().getBiomeEntries()).forEach(this.modelService::addBiome);
                world.getMapper().setBiomeCallback(this.modelService::addBiome);

                this.nodeManager.start();
            }

            this.pipeline = RenderPipelineFactory.createPipeline(this.properties, this.nodeManager, this.nodeCleaner, this.traversal, this::frexStillHasWork);
            this.pipeline.setupExtraModelBakeryData(this.modelService);

            // TAA 依赖最终管线定义，因此必须在管线创建后编译遍历 shader。
            this.traversal.lateStageCompile(this.pipeline);

            // 提前链接 Create 远景 shader，避免第一次绘制时阻塞游戏线程。
            DistantShaders.warmup(this.pipeline);


            var sectionRenderer = backendFactory.create(this.pipeline, this.modelService.getStore(), this.geometryData);
            this.pipeline.setSectionRenderer(sectionRenderer);
            this.viewportSelector = new ViewportSelector<>(sectionRenderer::createViewport);

            {
                int minSec = Minecraft.getInstance().level.getMinSection() >> 5;
                int maxSec = (Minecraft.getInstance().level.getMaxSection() - 1) >> 5;

                // Mine in Abyss 使用自定义世界分区范围。
                if (VoxyCommon.IS_MINE_IN_ABYSS) {
                    minSec = -8;
                    maxSec = 7;
                }

                this.renderDistanceTracker = new RenderDistanceTracker(this.getTopLevelNodeProcessRate(),
                        minSec,
                        maxSec,
                        this.nodeManager::addTopLevel,
                        this.nodeManager::removeTopLevel);

                this.setRenderDistance(VoxyConfig.CONFIG.sectionRenderDistance);
            }

            this.chunkBoundRenderer = new ChunkBoundRenderer(this.pipeline);
            // A Voxy-only reload must repopulate the mask even when Sodium kept its render list.
            var sodiumRenderer = SodiumWorldRenderer.instanceNullable();
            if (sodiumRenderer != null) {
                sodiumRenderer.scheduleTerrainUpdate();
            }

            Logger.info("Voxy render system created with " + this.geometryData.getMaxCapacity() + " geometry capacity, using pipeline '" + this.pipeline.getClass().getSimpleName() + "' with renderer '" + sectionRenderer.getClass().getSimpleName() + "'");
        } catch (RuntimeException e) {
            world.releaseRef();//If something goes wrong, we must release the world first
            throw e;
        }

        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, this.savedBufferBindings[i]);
        }

        for (int i = 0; i < 12; i++) {
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
            GlStateManager._bindTexture(0);
            glBindSampler(i, 0);
        }
        // The loop ends on unit 11. Custom OBJ renderers commonly bind their material on the
        // currently active unit and expect the conventional unit 0, so never leak unit 11.
        GlStateManager._activeTexture(GlConst.GL_TEXTURE0);
    }


    /** 根据原版相机和当前管线创建本帧 LOD 视口。 */
    public Viewport<?> setupViewport(Matrix4fc vanillaProjection,
                                     Matrix4fc modelView,
                                     double cameraX,
                                     double cameraY,
                                     double cameraZ) {
        var viewport = this.getViewport();
        if (viewport == null) {
            return null;
        }

        // Mine in Abyss 会把相机映射到自定义的分区坐标中。
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (((int) Math.floor(cameraX) >> 4) + 512) >> 10;
            cameraX -= sector << 14;
            cameraY += (16 + (256 - 32 - sector * 30)) * 16;
        }

        var voxyProjection = computeProjectionMat(this.properties, vanillaProjection, this.currentFarPlane());

        // 主相机视口永远采用主窗口尺寸（含渲染缩放），而不读取当前绑定的 GL 视口：这条路径
        // 在光影 beginLevelRendering 时执行，此时 GL 视口可能已是缩放后的内部目标（全屏下常见
        // 224x224），会让主视口尺寸错误、LOD 被压缩到角落，而副渲染通道反而拿到正确尺寸。
        int[] size = this.mainViewportSize();
        if (size == null) {
            return null;
        }
        int width = size[0];
        int height = size[1];

        viewport
                .setVanillaProjection(vanillaProjection)
                .setProjection(voxyProjection)
                .setModelView(modelView)
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        if (VoxyClient.getOcclusionDebugState() == 0) {
            viewport.frameId++;
        }

        return viewport;
    }

    // Packs that opt in can match the projection far plane to Voxy's configured section cube.
    // The diagonal keeps every corner inside the frustum; two chunks cover traversal padding.
    private float currentFarPlane() {
        if (this.activePipeline().useDynamicFarPlane()) {
            return (float) ((VoxyConfig.CONFIG.createLodRadius() + 32.0) * Math.sqrt(3.0));
        }
        return 16.0f * 3000.0f;
    }

    // 主窗口渲染目标尺寸，叠加光影管线的渲染缩放系数。刻意忽略 GL 视口：主相机以窗口尺寸为准，
    // 而 GL 视口在光影下并不可靠（捕获时为 0，或为缩放后的内部尺寸）。
    private int[] mainViewportSize() {
        var window = Minecraft.getInstance().getWindow();
        int width = window.getWidth();
        int height = window.getHeight();

        var factor = this.pipeline.getRenderScalingFactor();
        if (factor != null) {
            width = (int) (width * factor[0]);
            height = (int) (height * factor[1]);
        }
        if (width <= 0 || height <= 0) {
            Logger.error("Viewport width or height was zero, this is bad bad bad");
            return null;
        }
        return new int[]{width, height};
    }

    /** World renders carry explicit camera/target identity; legacy callers retain main-view semantics. */
    public Viewport<?> setupViewportForCurrentPass(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ) {
        if (IrisUtil.irisShadowActive()) return null;
        if (WorldRenderContext.current() != null) return this.getActiveViewport();
        return this.setupViewport(matrices.projection(), matrices.modelView(), cameraX, cameraY, cameraZ);
    }

    private Viewport<?> configureViewport(Viewport<?> viewport, ChunkRenderMatrices matrices, Matrix4f voxyProjection,
                                          double cameraX, double cameraY, double cameraZ, int width, int height) {
        viewport
                .setVanillaProjection(matrices.projection())
                .setProjection(voxyProjection)
                .setModelView(matrices.modelView())
                .setCamera(cameraX, cameraY, cameraZ)
                .setScreenSize(width, height)
                .update();

        if (VoxyClient.getOcclusionDebugState() == 0) {
            viewport.frameId++;
        }
        return viewport;
    }

    public static boolean visionEffectPresent() {
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        return mc.gameRenderer.getMainCamera().getEntity()
                    instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS)
                    || living.hasEffect(MobEffects.DARKNESS));
    }

    public static boolean restrictingMediumPresent() {
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        var camera = mc.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return true;
        }
        return camera.getEntity() instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS)
                    || living.hasEffect(MobEffects.DARKNESS));
    }

    private static volatile float lastRenderFogEnd = -1;
    private static volatile float lastRenderVanillaFar = -1;
    private static volatile boolean lastRenderSkipped;


    // 记录 Sodium 地形阶段实际使用的雾范围，LOD 交接和调试信息都以此为准。
    private static volatile float terrainFogEndAtRender = -1;
    private static volatile float terrainFogStartAtRender = -1;
    public static float getTerrainFogEndAtRender() {
        return terrainFogEndAtRender;
    }

    public static float getTerrainFogStartAtRender() {
        return terrainFogStartAtRender;
    }

    public static float getLastRenderFogEnd() {
        return lastRenderFogEnd;
    }

    public static float getLastRenderVanillaFar() {
        return lastRenderVanillaFar;
    }

    public static boolean wasLastRenderSkipped() {
        return lastRenderSkipped;
    }

    private static boolean visionRestricted() {
        var mc = Minecraft.getInstance();
        if (mc.options == null || mc.gameRenderer == null) {
            lastRenderSkipped = false;
            return false;
        }
        if (!(mc.gameRenderer.getMainCamera().getEntity()
                instanceof LivingEntity living)) {
            lastRenderSkipped = false;
            return false;
        }

        float viewDistance = mc.options.getEffectiveRenderDistance() * 16.0f;
        float restricted = Float.MAX_VALUE;

        var blindness = living.getEffect(MobEffects.BLINDNESS);
        if (blindness != null) {
            // 与原版失明雾的持续时间衰减保持一致。
            restricted = blindness.isInfiniteDuration()
                    ? 5.0f
                    : Mth.lerp(
                            Math.min(1.0f, blindness.getDuration() / 20.0f), viewDistance, 5.0f);
        }

        var darkness = living.getEffect(MobEffects.DARKNESS);
        if (darkness != null) {
            // 黑暗效果有 22 tick 的渐变，使用同一 blend factor 避免 LOD 瞬间消失。
            float partialTick = mc.getTimer().getGameTimeDeltaPartialTick(false);
            float f = Mth.lerp(
                    darkness.getBlendFactor(living, partialTick), viewDistance, 15.0f);
            restricted = Math.min(restricted, f);
        }

        lastRenderFogEnd = restricted;
        lastRenderVanillaFar = viewDistance;
        // 原版视距明显小于玩家正常视距时，LOD 才不再补充远景。
        boolean skip = restricted < viewDistance * 0.9f;
        lastRenderSkipped = skip;
        return skip;
    }

    public void renderOpaque(Viewport<?> viewport) {
        // Iris also invokes Sodium terrain rendering for its shadow pass. It must never replace the
        // pending main-camera viewport or run the normal fallback into a shadow framebuffer.
        if (IrisUtil.irisShadowActive()) {
            return;
        }
        if (viewport == null) {
            return;
        }
        if (viewport.width <= 0 || viewport.height <= 0) {
            Logger.error("Cannot render Voxy with an empty viewport");
            return;
        }
        terrainFogStartAtRender = RenderSystem.getShaderFogStart();
        terrainFogEndAtRender = RenderSystem.getShaderFogEnd();
        if (visionRestricted()) {
            this.pendingUnpatchedIrisViewport = null;
            return;
        }

        var context = WorldRenderContext.current();
        if ((context != null ? context.irisRendering : IrisUtil.irisShaderPackEnabled())
                && this.activePipeline() instanceof NormalRenderPipeline) {
            if (context != null) context.pendingFallback = viewport;
            else this.pendingUnpatchedIrisViewport = viewport;
            return;
        }

        this.pendingUnpatchedIrisViewport = null;
        this.renderOpaqueNow(viewport);
    }

    /**
     * Completes the compatibility fallback for shader packs which do not provide a Voxy pipeline.
     * Called once from Iris after its final pass, when the bound target once again contains ordinary
     * RGBA colour and Minecraft's shared world depth. Native Voxy-aware packs never enter this path.
     */
    public void renderUnpatchedIrisFallback() {
        var context = WorldRenderContext.current();
        Viewport<?> viewport = context == null ? this.pendingUnpatchedIrisViewport : context.pendingFallback;
        if (context != null) context.pendingFallback = null;
        else this.pendingUnpatchedIrisViewport = null;
        if (viewport == null || !(this.activePipeline() instanceof NormalRenderPipeline)) {
            return;
        }
        this.renderOpaqueNow(viewport);
    }

    private void renderOpaqueNow(Viewport<?> viewport) {
        if (!viewport.isMainViewport) {
            try (var state = new me.cortex.voxy.client.core.rendering.SecondaryGlState()) {
                glDisable(GL_SCISSOR_TEST);
                this.drawViewport(viewport);
            }
        } else this.drawViewport(viewport);
    }

    private void drawViewport(Viewport<?> viewport) {
        var activePipeline = this.activePipeline();

        // 轻量且幂等；在这里标记渲染线程，避免每次采样都引入 ThreadLocal 开销。
        VoxyProfile.markRenderThread();
        TimingStatistics.resetSamplers();

        TimingStatistics.all.start();
        // 标记帧正在执行，调试捕获器可以在 GPU/CPU 阻塞时采样。
        FrameProfiler.onViewportStart();
        GPUTiming.INSTANCE.beginViewport(FrameProfiler.frameId(), viewport.isMainViewport);
        TimingStatistics.main.start();

        for (int i = 0; i < this.savedBufferBindings.length; i++) {
            this.savedBufferBindings[i] = glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, i);
        }

        // 不依赖前一个渲染器留下的深度状态，避免边缘地形因比较函数或写掩码错误闪烁。
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(this.properties.closerEqualDepthCompare());
        GlStateManager._depthMask(true);

        int previousFramebuffer = GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int boundFramebuffer = previousFramebuffer;

        glGetIntegerv(GL_VIEWPORT, this.viewportDimensions);

        glViewport(0, 0, viewport.width, viewport.height);

        if (boundFramebuffer == 0) {
            throw new IllegalStateException("Cannot use the default framebuffer as cannot source from it");
        }

        activePipeline.preSetup(viewport);

        TimingStatistics.E.start();
        GPUTiming.INSTANCE.marker("CB");
        if (!VoxyClient.disableSodiumChunkRender() && !IrisUtil.irisShadowActive()) {
            WorldSection.setArrayPoolCapMiB(VoxyConfig.CONFIG.sectionArrayPoolMiB);
            this.getActiveChunkBoundRenderer().render(viewport);
        } else {
            viewport.depthBoundingBuffer.clear(this.properties.inverseClearDepth());
            viewport.invalidateChunkMask();
        }
        TimingStatistics.E.stop();


        GPUTiming.INSTANCE.marker();
        this.modelService.drainBlendPalette();
        // Run the LOD pipeline.
        if (viewport.isMainViewport) {
            // 完整管线：写入共享的光影深度帧缓冲，并驱动渲染距离跟踪与模型服务。副渲染通道
            // （Vista 电视等）绘制到自己的离屏目标，绝不能走这里——否则每帧都会把共享帧缓冲
            // 调整为电视机尺寸（破坏主相机水面 SSR），并把渲染距离中心移到电视机相机上。
            activePipeline.runPipeline(viewport, boundFramebuffer, this.viewportDimensions[2], this.viewportDimensions[3]);
        } else {
            // 副渲染通道（Vista 电视）：在独立缓冲中转换深度、绘制，再合成回调用方目标。
            glBindFramebuffer(GL_FRAMEBUFFER, boundFramebuffer);
            activePipeline.renderSecondary(viewport, boundFramebuffer, this.viewportDimensions[2], this.viewportDimensions[3]);
        }
        GPUTiming.INSTANCE.marker();


        TimingStatistics.main.stop();
        TimingStatistics.postDynamic.start();

        if (viewport.isMainViewport) {
            PrintfDebugUtil.tick();

            // 动态队列更新放在地形绘制后，减少渲染阶段的状态切换。
            {
                // 上传流 tick 只维护内存。
                UploadStream.INSTANCE.tick();

                this.renderDistanceTracker.setProcessRate(this.getTopLevelNodeProcessRate());
                while (this.renderDistanceTracker.setCenterAndProcess(viewport.cameraX, viewport.cameraZ)
                        && VoxyClient.isFrexActive()) {
                }
                TimingStatistics.H.start();
                // Done here as it allows less GL state resetup. The budget is read from config every
                // frame, so changing the LOD build pressure option is hot-reloadable and does not need
                // renderer recreation.
                long modelBakeBudget = this.getModelBakeBudgetNanos();
                this.modelService.tick(modelBakeBudget);
                TimingStatistics.H.stop();
            }
        }
        GPUTiming.INSTANCE.marker();
        TimingStatistics.postDynamic.stop();

        GPUTiming.INSTANCE.endViewport();

        glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previousFramebuffer);
        glViewport(this.viewportDimensions[0], this.viewportDimensions[1],
                this.viewportDimensions[2], this.viewportDimensions[3]);

        // 恢复 Minecraft 和外部 shader 依赖的 GL 状态。
        {
            glUseProgram(0);
            glEnable(GL_DEPTH_TEST);
            glDisable(GL_STENCIL_TEST);

            GlStateManager._glBindVertexArray(0);

            GlStateManager._activeTexture(GlConst.GL_TEXTURE1);
            for (int i = 0; i < 12; i++) {
                GlStateManager._activeTexture(GlConst.GL_TEXTURE0+i);
                GlStateManager._bindTexture(0);
                glBindSampler(i, 0);
            }
            GlStateManager._activeTexture(GlConst.GL_TEXTURE0);

            IrisUtil.clearIrisSamplers();

            // 恢复 LOD 绘制前保存的 shader-storage 绑定。
            for (int i = 0; i < this.savedBufferBindings.length; i++) {
                glBindBufferBase(GL_SHADER_STORAGE_BUFFER, i, this.savedBufferBindings[i]);
            }

        }

        TimingStatistics.all.stop();

        FrameProfiler.onViewportEnd(viewport.isMainViewport);
    }

    private long getModelBakeBudgetNanos() {
        int pressure = VoxyConfig.CONFIG.getRenderPressureLevel();
        int fps = Minecraft.getInstance().getFps();
        if (fps <= 0) {
            fps = 60;
        }

        int renderTasks = this.renderGen.getRunnableTaskCount();

        // When FPS is already low or the section generation queue is backing up, spend less
        // render-thread time on model baking. This keeps movement smooth and lets LOD catch up
        // when the CPU/GPU has headroom again.
        if (fps < 40 || renderTasks > 1_000) {
            return MODEL_BAKE_BUDGET_LOW_FPS[pressure];
        }
        if (fps < 55 || renderTasks > 400) {
            return MODEL_BAKE_BUDGET_BUSY[pressure];
        }
        return MODEL_BAKE_BUDGET_IDLE[pressure];
    }

    private int getTopLevelNodeProcessRate() {
        return TOP_LEVEL_NODE_PROCESS_RATE[VoxyConfig.CONFIG.getRenderPressureLevel()];
    }


    private void autoBalanceSubDivSize() {
        // Only raise quality when the mesh queue is under control.
        boolean canDecreaseSize = this.renderGen.getTaskCount() < 300;
        int fps = Minecraft.getInstance().getFps();
        int MIN_FPS = 55;
        int MAX_FPS = 65;
        float INCREASE_PER_SECOND = 60;
        float DECREASE_PER_SECOND = 30;
        if (fps < MIN_FPS) {
            VoxyConfig.CONFIG.subDivisionSize = Math.min(VoxyConfig.CONFIG.subDivisionSize + INCREASE_PER_SECOND / Math.max(1f, fps), VoxyConfig.MAX_SUBDIVISION_SIZE);
        }

        if (MAX_FPS < fps && canDecreaseSize) {
            VoxyConfig.CONFIG.subDivisionSize = Math.max(VoxyConfig.CONFIG.subDivisionSize - DECREASE_PER_SECOND / Math.max(1f, fps), VoxyConfig.MIN_SUBDIVISION_SIZE);
        }
    }

    public static float getRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    private Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base, float farPlane) {

        // Preserve projection changes applied by Minecraft, such as view bobbing.
        var rawMCProj = RenderSystem.getProjectionMatrix();
        var extraProjection = rawMCProj.invert(this.projectionScratch).mul(base);

        float near = getRenderDistance() <= 32.0f ? 8.0f : 16.0f;
        near = VoxyClient.disableSodiumChunkRender() ? 0.1f : near;

        // Keep SeedGen's required ModifyConstant target, while retaining dynamic shader-pack
        // distances when its far plane is still the upstream 48000-block baseline.
        float far = me.cortex.voxy.client.compat.seedgen.SeedGenCompat.projectionFarPlane(farPlane, 48000.0f);

        // Reverse-Z swaps the near and far mapping.
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                this.modifiedProjectionScratch.set(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }

    private boolean frexStillHasWork() {
        if (!VoxyClient.isFrexActive()) {
            return false;
        }
        UploadStream.INSTANCE.tick();
        this.modelService.tick(100_000_000);
        GL11.glFinish();
        return this.nodeManager.hasWork() || this.renderGen.getTaskCount() != 0 || !this.modelService.areQueuesEmpty();
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistanceTracker.setRenderDistance((int) Math.ceil(renderDistance + 1));
    }

    public Viewport<?> getViewport() {
        if (IrisUtil.irisShadowActive()) {
            return null;
        }
        return this.viewportSelector.getViewport();
    }

    public int getSableOcclusionDepthTexture() {
        return this.pipeline.getSableOcclusionDepthTexture();
    }

    //The pipeline type is fixed at world entry (shader state at creation time); path selection for
    //the distant train/track renderers must follow it, not the live shader toggle.
    public boolean isIrisPipeline() {
        return this.pipeline instanceof IrisVoxyRenderPipeline;
    }

    public void addDebugInfo(List<String> debug) {
        me.cortex.voxy.client.compat.create.DistantMesh.addDebug(debug);
        debug.add("Buf/Tex [#/Mb]: [" + GlBuffer.getCount() + "/" + (GlBuffer.getTotalSize()/1_000_000) + "],[" + GlTexture.getCount() + "/" + (GlTexture.getEstimatedTotalSize()/1_000_000)+"]");
        //Sodium-visible sections drive the hole-punch mask's fill cost (see the "CB" GPU marker)
        debug.add("Mask sections (sodium visible): " + this.chunkBoundRenderer.getLastRenderedSectionCount());
        var maskView = this.viewportSelector.getViewport();
        debug.add("mask " + maskView.chunkMaskWidth + "x" + maskView.chunkMaskHeight
                + " | hiz " + maskView.hiZBuffer.describe());
        debug.add("maskReuse: " + this.chunkBoundRenderer.describeReuseState());
        debug.add("arrayPool: " + WorldSection.getReuseCacheCount() / 4.0
                + "/" + VoxyConfig.CONFIG.sectionArrayPoolMiB + " MiB | miss "
                + PerfStats.sectionArrayPoolMiss.sum()
                + " overflow " + PerfStats.sectionArrayPoolOverflow.sum());
        {
            this.modelService.addDebugData(debug);
            this.renderGen.addDebugData(debug);
            this.nodeManager.addDebug(debug);
            this.pipeline.addDebug(debug);
        }
        {
            TimingStatistics.update();
            debug.add("Voxy frame runtime (millis): " + TimingStatistics.dynamic.pVal() + ", " + TimingStatistics.main.pVal()+ ", " + TimingStatistics.postDynamic.pVal()+ ", " + TimingStatistics.all.pVal());
            debug.add("Voxy LOD build pressure: " + VoxyConfig.CONFIG.getRenderPressureLevel() + ", model bake budget ns: " + this.getModelBakeBudgetNanos() + ", node process rate: " + this.getTopLevelNodeProcessRate());
            debug.add("Extra time: " + TimingStatistics.A.pVal() + ", " + TimingStatistics.B.pVal() + ", " + TimingStatistics.C.pVal() + ", " + TimingStatistics.D.pVal());
            debug.add("Extra 2 time: " + TimingStatistics.E.pVal() + ", " + TimingStatistics.F.pVal() + ", " + TimingStatistics.G.pVal() + ", " + TimingStatistics.H.pVal() + ", " + TimingStatistics.I.pVal());
        }
        debug.add(GPUTiming.INSTANCE.getDebug());
        PrintfDebugUtil.addToOut(debug);
    }

    public void shutdown() {
        me.cortex.voxy.client.compat.pingwheel.PingWheelCompat.stop();
        this.pendingUnpatchedIrisViewport = null;
        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();
        Logger.info("Shutting down rendering");
        try {
            //Cleanup callbacks
            this.worldIn.setDirtyCallback(null);
            this.worldIn.getMapper().setBiomeCallback(null);
            this.worldIn.getMapper().setStateCallback(null);

            this.nodeManager.stop();

            this.modelService.shutdown();
            this.renderGen.shutdown();
            this.releaseSecondaryPasses();
            this.traversal.free();
            this.nodeCleaner.free();
            this.geometryData.free();
            if (((BasicSectionGeometryData)this.geometryData).isExternalGeometryBuffer) {
                RenderResourceReuse.giveBackGeometryBuffer(((BasicSectionGeometryData)this.geometryData).getGeometryBuffer());
            }

            this.chunkBoundRenderer.free();

            this.viewportSelector.free();
        } catch (Exception e) {Logger.error("Error shutting down renderer components", e);}
        Logger.info("Shutting down render pipeline");
        try {
            this.pipeline.free();
        } catch (Exception e) {
            Logger.error("Error releasing render pipeline", e);
        }

        Logger.info("Flushing download stream");
        DownloadStream.INSTANCE.flushWaitClear();

        //Release hold on the world
        this.worldIn.releaseRef();
        Logger.info("Render shutdown completed");
    }

    public WorldEngine getEngine() {
        return this.worldIn;
    }
}
