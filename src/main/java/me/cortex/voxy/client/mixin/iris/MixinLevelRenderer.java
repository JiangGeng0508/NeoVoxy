package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glViewport;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "renderLevel", at = @At("HEAD"), order = 100)
    private void voxy$injectIrisCompat(
            DeltaTracker tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        if (IrisUtil.irisShaderPackEnabled()) {
            var renderer = ((IGetVoxyRenderSystem) this).voxy$getRenderSystem();
            if (renderer != null) {
                //Fix the fucking viewport dims, fuck iris. Iris can leave a 0-sized viewport at
                // capture time on the main pass. Only repair that broken case: secondary level
                // renders (Vista TVs/mirrors, freecam...) deliberately run with a small viewport
                // matching their own off-screen target, and stomping it here renders everything
                // with the wrong projection size.
                int[] dims = new int[4];
                glGetIntegerv(GL_VIEWPORT, dims);
                if (dims[2] <= 0 || dims[3] <= 0) {
                    glViewport(0, 0, Minecraft.getInstance().getMainRenderTarget().width, Minecraft.getInstance().getMainRenderTarget().height);
                }

                var pos = camera.getPosition();
                IrisUtil.CAPTURED_VIEWPORT_PARAMETERS = new IrisUtil.CapturedViewportParameters(new ChunkRenderMatrices(projectionMatrix, positionMatrix), pos.x, pos.y, pos.z);
            }
        }
    }
}
