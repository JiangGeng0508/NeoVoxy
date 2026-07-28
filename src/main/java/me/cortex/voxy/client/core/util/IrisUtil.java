package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.neoforged.fml.ModList;

import java.io.IOException;

public class IrisUtil {
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices.projection(), this.matrices.modelView(), this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;
    private static boolean initializingBlockMaterialIds;

    public static final boolean IRIS_INSTALLED = ModList.get().isLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;

    private static boolean irisShadowActive0() {
        return ShadowRenderer.ACTIVE;
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && irisShadowActive0();
    }

    private static void clearIrisSamplers0() {
        for (int i = 0; i < 16; i++) {
            IrisRenderSystem.bindSamplerToUnit(i, 0);
        }
    }

    public static void clearIrisSamplers() {
        if (IRIS_INSTALLED) {
            clearIrisSamplers0();
        }
    }

    public static void reload() {
        if (!IRIS_INSTALLED) {
            return;
        }
        try {
            if (IrisApi.getInstance().isShaderPackInUse() || IrisApi.getInstance().getConfig().areShadersEnabled()) {
                Iris.reload();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload Iris", e);
        }
    }

    private static boolean irisShaderPackEnabled0() {
        return Iris.isPackInUseQuick();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && irisShaderPackEnabled0();
    }

    public static boolean irisShadersEnabledInConfig() {
        return irisShaderPackEnabled();
    }

    public static void disableIrisShaders() {
        if (IRIS_INSTALLED) {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        }
    }

    public static void beginBlockMaterialInitialization() {
        initializingBlockMaterialIds = true;
    }

    public static void endBlockMaterialInitialization() {
        initializingBlockMaterialIds = false;
    }

    public static boolean isBlockMaterialInitialization() {
        return initializingBlockMaterialIds;
    }
}
