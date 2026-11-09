package me.cortex.voxy.client.iris;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.function.Supplier;

import static net.irisshaders.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public class VoxyUniforms {

    private static me.cortex.voxy.client.core.rendering.ViewHistory history() {
        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer instanceof IGetVoxyRenderSystem access && access.voxy$getRenderSystem() != null) {
            var viewport = access.voxy$getRenderSystem().getActiveViewport();
            if (viewport != null) return viewport.history;
        }
        return new me.cortex.voxy.client.core.rendering.ViewHistory();
    }

    public static Matrix4f getViewProjection() { return new Matrix4f(history().viewProjection); }
    public static Matrix4f getModelView() { return new Matrix4f(history().modelView); }
    public static Matrix4f getProjection() { return new Matrix4f(history().projection); }

    private static LodBoundaryFade.Distances boundary() {
        var context = me.cortex.voxy.client.core.rendering.WorldRenderContext.current();
        return context != null && context.secondary ? new LodBoundaryFade.Distances(0, 0) : LodBoundaryFade.getDistances();
    }

    public static void addUniforms(UniformHolder uniforms) {
        uniforms
                .uniform1i(PER_FRAME, "vxRenderDistance", ()->Math.round(VoxyConfig.CONFIG.sectionRenderDistance*32))//In chunks
                // Used by the injected Iris terrain-cutout and shadow shaders. These must be
                // registered even though the opaque LOD handoff itself uses the stencil pass.
                .uniform1f(PER_FRAME, "voxyLodBoundaryFadeStart", () -> boundary().fadeStart())
                .uniform1f(PER_FRAME, "voxyLodBoundaryFadeEnd", () -> boundary().fadeEnd())
                .uniformMatrix(PER_FRAME, "vxViewProj", VoxyUniforms::getViewProjection)
                .uniformMatrix(PER_FRAME, "vxViewProjInv", new Inverted(VoxyUniforms::getViewProjection))
                .uniformMatrix(PER_FRAME, "vxViewProjPrev", () -> history().previousViewProjection)
                .uniformMatrix(PER_FRAME, "vxModelView", VoxyUniforms::getModelView)
                .uniformMatrix(PER_FRAME, "vxModelViewInv", new Inverted(VoxyUniforms::getModelView))
                .uniformMatrix(PER_FRAME, "vxModelViewPrev", () -> history().previousModelView)
                .uniformMatrix(PER_FRAME, "vxProj", VoxyUniforms::getProjection)
                .uniformMatrix(PER_FRAME, "vxProjInv", new Inverted(VoxyUniforms::getProjection))
                .uniformMatrix(PER_FRAME, "vxProjPrev", () -> history().previousProjection);

    }




    private record Inverted(Supplier<Matrix4fc> parent) implements Supplier<Matrix4fc> {
        private Inverted(Supplier<Matrix4fc> parent) {
            this.parent = parent;
        }

        public Matrix4fc get() {
            Matrix4f copy = new Matrix4f(this.parent.get());
            copy.invert();
            return copy;
        }

        public Supplier<Matrix4fc> parent() {
            return this.parent;
        }
    }

}
