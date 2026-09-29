package me.cortex.voxy.client.mixin.simulated;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.compat.simulated.DistantLaserRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserRenderer", remap = false)
public abstract class MixinAbstractLaserRenderer {
    @Inject(method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), require = 0)
    private void voxy$beginLaserCapture(@Coerce Object blockEntity, float partialTicks, PoseStack pose,
                                        MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        DistantLaserRenderer.beginCapture(blockEntity);
    }

    @Inject(method = "createLaser(Lorg/joml/Vector4f;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;FF)V",
            at = @At("HEAD"), require = 0)
    private void voxy$captureLaser(Vector4f color, PoseStack pose, MultiBufferSource buffer,
                                   float maxLength, float length, CallbackInfo ci) {
        DistantLaserRenderer.capture(pose, color, maxLength, length);
    }

    @Inject(method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void voxy$endLaserCapture(@Coerce Object blockEntity, float partialTicks, PoseStack pose,
                                      MultiBufferSource buffer, int light, int overlay, CallbackInfo ci) {
        DistantLaserRenderer.endCapture();
    }
}
