package me.cortex.voxy.client.mixin.iris;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.beacon.DistantBeaconRenderer;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShadowRenderingState.class, remap = false)
public abstract class MixinShadowRenderingState {
    @Inject(method = "renderBlockEntities", at = @At("RETURN"), cancellable = true)
    private static void voxy$renderDistantBeaconShadows(
            ShadowRenderer shadowRenderer, RenderBuffers buffers, PoseStack poses, Camera camera,
            double cameraX, double cameraY, double cameraZ, float tickDelta,
            boolean hasEntityFrustum, boolean lightsOnly, CallbackInfoReturnable<Integer> cir) {
        double distance = Math.max(0, ShadowRenderingState.getRenderDistance()) * 16.0;
        int extra = DistantBeaconRenderer.renderShadowCasters(
                buffers, poses, cameraX, cameraY, cameraZ, distance);
        cir.setReturnValue(cir.getReturnValue() + extra);
    }
}
