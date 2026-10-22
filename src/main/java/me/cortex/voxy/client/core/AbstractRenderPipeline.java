package me.cortex.voxy.client.core;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.TimingStatistics;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.LodViewState;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.util.GPUTiming;
import me.cortex.voxy.common.util.TrackedObject;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11C.GL_ALWAYS;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.glColorMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glStencilFunc;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glStencilOp;
import static org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL42.GL_LEQUAL;
import static org.lwjgl.opengl.GL42.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL42.glDepthFunc;
import static org.lwjgl.opengl.GL42.*;
import static org.lwjgl.opengl.GL45.glClearNamedFramebufferfi;
import static org.lwjgl.opengl.GL45.glGetNamedFramebufferAttachmentParameteri;
import static org.lwjgl.opengl.GL45C.glBindTextureUnit;

/** LOD 渲染管线的公共生命周期、深度交接和区段遍历实现。 */
public abstract class AbstractRenderPipeline extends TrackedObject {
    public final RenderProperties properties;
    private final BooleanSupplier frexStillHasWork;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    protected AbstractSectionRenderer<?,?> sectionRenderer;

    // Command-list reuse also depends on resolution, subdivision and the near-detail range.
    // MVP has no world translation, so camera position and viewport identity remain separate.
    private final LodViewState lastBuildView = new LodViewState();
    private double lastBuildCamX, lastBuildCamY, lastBuildCamZ;
    //Vanilla folds the decaying view bob into the projection for ~10 s after the player stops, so a
    //bit-exact MVP compare would keep the hold off for that long. The tolerance is far below a
    //pixel and the baseline is the last BUILD, so drift cannot accumulate past it.
    private static final float HOLD_MVP_TOLERANCE = 1.0e-6f;
    private boolean hasBuiltCommandLists;
    private Viewport<?> lastBuildViewport;
    private int consecutiveHolds;
    private long heldFrameCount, builtFrameCount;

    private final FullscreenBlit depthStencilSetup;
    private final FullscreenBlit sentinelRestore;

    public final DepthFramebuffer fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

    // Secondary cameras need the same depth conversion as the main camera, but must never
    // resize its targets (Iris keeps using those textures for later passes such as water SSR).
    private DepthFramebuffer secondaryFb;
    private GlTexture secondaryColour;
    private FullscreenBlit secondaryBlit;
    private final Matrix4f secondaryTargetTransform = new Matrix4f();

    protected final boolean deferTranslucency;

    private static final int DEPTH_SAMPLER = glGenSamplers();
    static {
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glSamplerParameteri(DEPTH_SAMPLER, org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
        glSamplerParameteri(DEPTH_SAMPLER, org.lwjgl.opengl.GL12C.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE);
    }

    protected AbstractRenderPipeline(RenderProperties properties, AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal, BooleanSupplier frexSupplier, boolean deferTranslucency) {
        this.properties = properties;
        this.frexStillHasWork = frexSupplier;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
        this.deferTranslucency = deferTranslucency;

        this.depthStencilSetup = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/setup_stencil_depth.frag");
        this.sentinelRestore = new FullscreenBlit(properties, "voxy:post/fullscreen2.vert", "voxy:post/depth0.frag");
    }

    //Allows pipelines to configure model baking system
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {}

    public final void setSectionRenderer(AbstractSectionRenderer<?,?> sectionRenderer) {
        if (this.sectionRenderer != null) throw new IllegalStateException();
        this.sectionRenderer = sectionRenderer;
    }

    //Called before the pipeline starts running, used to update uniforms etc
    public void preSetup(Viewport<?> viewport) {

    }

    protected abstract int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight);
    protected abstract void postOpaquePreTranslucent(Viewport<?> viewport, int sourceFrameBuffer);
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    public void runPipeline(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        int depthTexture = this.setup(viewport, sourceFrameBuffer, srcWidth, srcHeight);

        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        GPUTiming.INSTANCE.marker("RO");
        rs.renderOpaque(viewport);
        var occlusionDebug = VoxyClient.getOcclusionDebugState();
        boolean built = true;
        if (occlusionDebug==0) {
            GPUTiming.INSTANCE.marker("I");
            built = this.innerPrimaryWork(viewport, depthTexture);
            GPUTiming.INSTANCE.marker();
        }

        if (built) {
            if (occlusionDebug<=1) {
                TimingStatistics.G.start();
                rs.buildDrawCalls(viewport);
                TimingStatistics.G.stop();
            }

            GPUTiming.INSTANCE.marker("TP");
            rs.renderTemporal(viewport);

            //Advanced only after buildDrawCalls consumed the old value: the occlusion raster inside
            //it compares visibility stamps against the PREVIOUS build's frameId, then restamps with
            //the current one - which becomes the baseline for the next build.
            viewport.prevBuildFrameId = viewport.frameId;
            this.lastBuildView.set(viewport.MVP, viewport.width, viewport.height,
                    VoxyConfig.CONFIG.subDivisionSize, (Minecraft.getInstance().options.renderDistance().get() + 2) * 16.0);
            this.lastBuildCamX = viewport.cameraX;
            this.lastBuildCamY = viewport.cameraY;
            this.lastBuildCamZ = viewport.cameraZ;
            this.hasBuiltCommandLists = true;
            this.lastBuildViewport = viewport;
            this.consecutiveHolds = 0;
            this.builtFrameCount++;
        } else {
            //Held frame: renderOpaque above already replayed the previous command lists (they draw
            //the identical scene - same camera, same geometry), and the translucent draw below
            //replays its lane the same way. The temporal pass is skipped outright: it exists to
            //paper over sections whose visibility CHANGED this frame, and on a held frame nothing
            //did.
            this.consecutiveHolds++;
            this.heldFrameCount++;

        }

        rs.postOpaquePreperation(viewport);

        me.cortex.voxy.client.compat.LodPipelineHooks.beforeTranslucent(this, viewport, this.properties.closerEqualDepthCompare());

        this.postOpaquePreTranslucent(viewport, sourceFrameBuffer);

        me.cortex.voxy.client.compat.LodPipelineHooks.translucent(
                this, viewport, this.properties.closerEqualDepthCompare());

        GPUTiming.INSTANCE.marker("RT");

        if (!this.deferTranslucency) {
            rs.renderTranslucent(viewport);
        }
        GPUTiming.INSTANCE.marker();

        this.finish(viewport, sourceFrameBuffer, srcWidth, srcHeight);
        glBindFramebuffer(GL_FRAMEBUFFER, sourceFrameBuffer);
    }

    // Secondary cameras use an isolated LOD target. Both HiZ and geometry must see source depth
    // reprojected into the LOD projection; directly comparing their different near/far planes makes
    // distant LOD appear in front of vanilla chunks. Only the final composite writes to the caller.
    public void renderSecondary(Viewport<?> viewport, int sourceFramebuffer) {
        var rs = ((AbstractSectionRenderer)this.sectionRenderer);
        if (rs == null) {
            return;
        }

        int depthTex = this.setupSecondary(viewport, sourceFramebuffer);
        viewport.hiZBuffer.buildMipChain(depthTex, viewport.width, viewport.height);

        //Build and draw this camera's current list in the same frame.
        this.traversal.doTraversal(viewport);
        this.secondaryFb.bind();
        rs.buildDrawCalls(viewport);
        rs.renderOpaque(viewport);
        viewport.prevBuildFrameId = viewport.frameId;
        this.finishSecondary(viewport, sourceFramebuffer);
    }

    protected int setupSecondary(Viewport<?> viewport, int sourceFramebuffer) {
        if (this.secondaryFb == null) {
            this.secondaryFb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);
            this.secondaryBlit = new FullscreenBlit(this.properties,
                    "voxy:post/blit_texture_depth_cutout.frag", builder -> builder.define("EMIT_COLOUR"));
        }
        if (this.secondaryFb.resize(viewport.width, viewport.height)) {
            if (this.secondaryColour != null) this.secondaryColour.free();
            this.secondaryColour = new GlTexture().store(GL_RGBA8, 1, viewport.width, viewport.height);
            this.secondaryFb.framebuffer.bind(GL_COLOR_ATTACHMENT0, this.secondaryColour).verify();
            org.lwjgl.opengl.GL45C.glTextureParameteri(this.secondaryColour.id, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            org.lwjgl.opengl.GL45C.glTextureParameteri(this.secondaryColour.id, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        }

        glViewport(0, 0, viewport.width, viewport.height);
        glDisable(GL_BLEND);
        glColorMask(true, true, true, true);
        glDepthMask(true);
        glStencilMask(0xFF);
        org.lwjgl.opengl.GL45C.glClearNamedFramebufferfv(this.secondaryFb.framebuffer.id,
                GL_COLOR, 0, new float[]{0, 0, 0, 0});
        this.initDepthStencil(viewport, sourceFramebuffer, this.secondaryFb.framebuffer.id,
                viewport.width, viewport.height, viewport.width, viewport.height);
        return this.secondaryFb.getDepthTex().id;
    }

    protected void finishSecondary(Viewport<?> viewport, int sourceFramebuffer) {
        // Untouched pixels have alpha zero, so vanilla colour/depth survive without a round trip.
        // Drawn pixels return to the caller's projection for subsequent entities/translucency.
        glDisable(GL_BLEND);
        glDepthMask(true);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        glBindTextureUnit(3, this.secondaryColour.id);
        glBindSampler(0, DEPTH_SAMPLER);
        glBindSampler(3, DEPTH_SAMPLER);
        transformBlitDepth(this.secondaryBlit, this.secondaryFb.getDepthTex().id, sourceFramebuffer,
                viewport, this.secondaryTargetTransform.set(viewport.vanillaProjection).mul(viewport.modelView));
        glBindSampler(0, 0);
        glBindSampler(3, 0);
    }

    //The depth attachment point depends on whether the framebuffer carries a combined
    //depth-stencil texture (vanilla/Iris) or a plain depth texture; querying the wrong point
    //returns texture 0 and the secondary HiZ would be built from nothing.
    private static int getDepthAttachment(int framebuffer) {
        int depthObject = glGetNamedFramebufferAttachmentParameteri(
                framebuffer,
                GL_DEPTH_ATTACHMENT,
                GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME
        );
        return depthObject != 0 ? GL_DEPTH_ATTACHMENT : GL_DEPTH_STENCIL_ATTACHMENT;
    }

    protected void initDepthStencil(Viewport<?> viewport, int sourceFrameBuffer, int targetFb, int srcWidth, int srcHeight, int width, int height) {
        glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, this.properties.clearDepth(), 1);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, targetFb);

        //If pixel passes, update stencil to 0 and set depth to the reprojected source depth
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);

        glEnable(GL_STENCIL_TEST);
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        glStencilFunc(GL_ALWAYS, 0, 0xFF);
        glStencilMask(0xFF);


        this.depthStencilSetup.bind();
        int depthTexture = glGetNamedFramebufferAttachmentParameteri(sourceFrameBuffer,
                getDepthAttachment(sourceFrameBuffer), GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (viewport.isMainViewport) {
            this.lastSourceDepthTex = depthTexture;
            this.lastSrcWidth = srcWidth;
            this.lastSrcHeight = srcHeight;
        }
        glBindTextureUnit(0, depthTexture);
        glBindSampler(0, DEPTH_SAMPLER);
        glUniform2f(1,((float)width)/srcWidth, ((float)height)/srcHeight);
        INVERSE_MVP.set(viewport.vanillaProjection)
                .mul(viewport.modelView)
                .invert()
                .getToAddress(SCRATCH);
        nglUniformMatrix4fv(2, 1, false, SCRATCH);
        viewport.MVP.getToAddress(SCRATCH);
        nglUniformMatrix4fv(3, 1, false, SCRATCH);
        boolean halfNdc = RenderProperties.windowIsHalfNdc();
        float ndcRemapScale = halfNdc ? 0.5f : 1.0f;
        float ndcRemapBias = halfNdc ? 0.5f : 0.0f;
        //xy: voxy ndc->window; zw: the inverse map for the source depth being unprojected - both
        //sides share the one clip-control mode, and only z is affected by it
        glUniform4f(4, ndcRemapScale, ndcRemapBias,
                1.0f / ndcRemapScale, -ndcRemapBias / ndcRemapScale);
        // Main-camera fade distances must not relinquish vanilla coverage in a remote camera.
        var boundary = viewport.isMainViewport ? LodBoundaryFade.getDistances() : new LodBoundaryFade.Distances(0, 0);
        glUniform1f(6, boundary.fadeStart());
        glUniform1f(7, boundary.fadeEnd());
        int cameraBlockX = (int) Math.floor(viewport.cameraX);
        int cameraBlockY = (int) Math.floor(viewport.cameraY);
        int cameraBlockZ = (int) Math.floor(viewport.cameraZ);
        glUniform3i(8, cameraBlockX, cameraBlockY, cameraBlockZ);
        glUniform3f(9,
                (float) (viewport.cameraX - cameraBlockX),
                (float) (viewport.cameraY - cameraBlockY),
                (float) (viewport.cameraZ - cameraBlockZ));
        glUniform1i(10, 0);
        glDepthMask(true);
        glColorMask(false,false,false,false);
        this.depthStencilSetup.blit();

        if (boundary.enabled() && this.useBoundaryGuardPass()) {
            glStencilMask(0x00);
            glUniform1i(10, 1);
            this.depthStencilSetup.blit();
            glUniform1i(10, 0);
            glStencilMask(0xFF);
        }


        glDepthFunc(this.properties.closerEqualDepthCompare());
        glColorMask(true,true,true,true);

        glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        glStencilFunc(GL_EQUAL, 1, 0x1);
    }

    protected boolean useBoundaryGuardPass() {
        return true;
    }

    protected void restoreSentinelDepth() {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        glColorMask(false,false,false,false);
        glEnable(GL_STENCIL_TEST);
        //Full-mask EQUAL,0: only untouched vanilla-covered pixels revert; pixels the hook meshes
        //tagged (3) keep their real depth so SSAO/composite/the vanilla handback carry them
        glStencilFunc(GL_EQUAL, 0, 0xFF);
        this.sentinelRestore.blit();
        glStencilFunc(GL_EQUAL, 1, 0x1);
        glDepthFunc(this.properties.closerEqualDepthCompare());
        glColorMask(true,true,true,true);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(4*4*4);
    private static final Matrix4f INVERSE_MVP = new Matrix4f();
    protected static void transformBlitDepth(FullscreenBlit blitShader, int srcDepthTex, int dstFB, Viewport<?> viewport, Matrix4f targetTransform) {
        glDisable(GL_STENCIL_TEST);
        glBindFramebuffer(GL30.GL_FRAMEBUFFER, dstFB);

        blitShader.bind();
        glBindTextureUnit(0, srcDepthTex);
        viewport.MVP.invert(INVERSE_MVP).getToAddress(SCRATCH);
        nglUniformMatrix4fv(1, 1, false, SCRATCH);//inverse fromProjection
        targetTransform.getToAddress(SCRATCH);//new Matrix4f(tooProjection).mul(vp.modelView).get(data);
        nglUniformMatrix4fv(2, 1, false, SCRATCH);//tooProjection

        glEnable(GL_DEPTH_TEST);
        blitShader.blit();
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_DEPTH_TEST);
    }

    protected boolean innerPrimaryWork(Viewport<?> viewport, int depthBuffer) {
        //All hold conditions are evaluated against the LAST BUILD, not the last frame: the command
        //lists being reused are the last build's, so drift accumulates against that baseline.
        //Camera position is compared exactly (a still entity lerps to the identical value); any
        //translation, rotation, fov change or projection-jittering shader pack fails the key and
        //forces a build, which is the safe direction.
        boolean holdEligible = VoxyConfig.CONFIG.experimentalCmdListHold
                && this.hasBuiltCommandLists
                && this.lastBuildViewport == viewport
                && this.consecutiveHolds < VoxyConfig.CONFIG.cmdListHoldMaxFrames - 1
                && viewport.cameraX == this.lastBuildCamX
                && viewport.cameraY == this.lastBuildCamY
                && viewport.cameraZ == this.lastBuildCamZ
                && this.lastBuildView.matches(viewport.MVP, viewport.width, viewport.height,
                        VoxyConfig.CONFIG.subDivisionSize, (Minecraft.getInstance().options.renderDistance().get() + 2) * 16.0,
                        HOLD_MVP_TOLERANCE);

        boolean built = false;
        boolean mipChainBuilt = false;
        do {
            TimingStatistics.main.stop();
            TimingStatistics.dynamic.start();

            TimingStatistics.D.start();
            //Tick download stream
            DownloadStream.INSTANCE.tick();
            TimingStatistics.D.stop();

            //Ticked on held frames too - this drains worker results and issues the geometry
            //uploads. Consuming ANY result set is a hard build trigger, not a heuristic: commands
            //bake baseVertex offsets into the geometry arena, and the moves/frees in a consumed
            //batch leave held command lists pointing at stale memory.
            if (this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner)) {
                holdEligible = false;
            }
            //glFlush();

            this.traversal.tickRequestClock();
            if (!holdEligible) {
                //The cleaner's visibilityId is the LRU clock the traversal stamps rendered nodes
                //with; a held frame renders the same nodes again, so the clock only ticks on
                //frames that traverse. The eviction it queues lands through nodeManager.tick,
                //which forces a build by itself.
                this.nodeCleaner.tick(this.traversal.getNodeBuffer());//Probably do this here??
            }

            TimingStatistics.dynamic.stop();
            TimingStatistics.main.start();

            if (!holdEligible) {
                if (!mipChainBuilt) {
                    //Compute the mip chain
                    GPUTiming.INSTANCE.marker("HZ");
                    viewport.hiZBuffer.buildMipChain(depthBuffer, viewport.width, viewport.height);
                    mipChainBuilt = true;
                }
                GPUTiming.INSTANCE.marker("TV");

                glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);

                TimingStatistics.F.start();
                this.traversal.doTraversal(viewport);
                TimingStatistics.F.stop();
                built = true;
            }

            if (!this.frexStillHasWork.getAsBoolean()) break;
            //frex signals it needs further traversal passes; a held frame never traverses, so
            //honouring the hold here would spin this loop with no way for that work to finish
            holdEligible = false;
        } while (true);
        return built;
    }

    @Override
    protected void free0() {
        if (this.secondaryFb != null) {
            this.secondaryBlit.delete();
            this.secondaryFb.free();
            if (this.secondaryColour != null) this.secondaryColour.free();
        }
        this.fb.free();
        this.sectionRenderer.free();
        this.depthStencilSetup.delete();
        this.sentinelRestore.delete();
        super.free0();
    }

    public void addDebug(List<String> debug) {
        this.sectionRenderer.addDebug(debug);
        this.traversal.addDebug(debug);
        RenderStatistics.addDebug(debug);
        debug.add("cmdHold: " + VoxyConfig.CONFIG.experimentalCmdListHold + " held " + this.heldFrameCount
                + " built " + this.builtFrameCount + " run " + this.consecutiveHolds);
    }

    //Binds the framebuffer and any other bindings needed for rendering
    public abstract void setupAndBindOpaque(Viewport<?> viewport);
    public abstract void setupAndBindTranslucent(Viewport<?> viewport);


    public void bindUniforms() {
        this.bindUniforms(-1);
    }

    public void bindUniforms(int index) {
    }

    public boolean hasTAA() {
        return false;
    }

    //null means no function, otherwise return the taa injection function
    public String taaFunction(String functionName) {
        return this.taaFunction(-1, functionName);
    }

    public String taaFunction(int uboBindingPoint, String functionName) {
        return null;
    }

    //null means dont transform the shader
    public String patchOpaqueShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    //Returning null means apply the same patch as the opaque
    public String patchTranslucentShader(AbstractSectionRenderer<?,?> renderer, String input) {
        return null;
    }

    public float[] getRenderScalingFactor() {
        return null;
    }

    public boolean useDynamicFarPlane() {
        return false;
    }

    //Depth texture LOD geometry renders into, for sable contraption depth-occlusion compositing.
    //Default none; only the Iris pipeline provides one.
    public int getSableOcclusionDepthTexture() {
        return 0;
    }

    //Inputs of the last initDepthStencil, for the occlusion debug recorder
    private int lastSourceDepthTex;
    private int lastSrcWidth, lastSrcHeight;
    public final int debugSourceDepthTex() { return this.lastSourceDepthTex; }
    public final int debugSrcWidth() { return this.lastSrcWidth; }
    public final int debugSrcHeight() { return this.lastSrcHeight; }

}
