package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;

public class IrisUtil {
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices.projection(), this.matrices.modelView(), this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = false;
    public static final boolean SHADER_SUPPORT = false;

    public static boolean irisShadowActive() {
        return false;
    }

    public static void clearIrisSamplers() {
    }
    public static void reload() {
    }

    public static boolean irisShaderPackEnabled() {
        return false;
    }
    public static boolean irisShadersEnabledInConfig() {
        return false;
    }
    public static void disableIrisShaders() {
    }
}
