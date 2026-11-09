package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Render-thread stack. A feed may render while the main world's render is suspended. */
public final class WorldRenderContext implements AutoCloseable {
    private static WorldRenderContext current;
    private final WorldRenderContext parent;
    public final RenderTarget target;
    public final boolean secondary;
    public final Matrix4f projection, modelView;
    public final double x, y, z;
    public AbstractRenderPipeline pipeline;
    public Viewport<?> viewport;
    public ChunkBoundRenderer chunkBounds;
    public Object irisOwner;
    public me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData irisData;
    public boolean irisRendering;
    public Viewport<?> pendingFallback;

    public WorldRenderContext(RenderTarget target, boolean secondary, Matrix4fc projection,
                              Matrix4fc modelView, double x, double y, double z) {
        this.parent = current;
        this.target = target;
        this.secondary = secondary || parent != null;
        this.projection = new Matrix4f(projection);
        this.modelView = new Matrix4f(modelView);
        this.x = x;
        this.y = y;
        this.z = z;
        current = this;
    }

    public static WorldRenderContext current() { return current; }

    @Override public void close() {
        if (current != this) throw new IllegalStateException("World render contexts closed out of order");
        current = parent;
    }
}
