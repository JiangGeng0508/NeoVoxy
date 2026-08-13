package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogRenderer.FogMode;

import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.RenderSystem;

@Mixin(value = FogRenderer.class, remap = true)
public class MixinFogRenderer {
    @Inject(
        method = "setupFog(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/FogRenderer$FogMode;FZF)V",
        at = @At("TAIL")
    )
    private static void voxy$overrideFog(
        Camera camera,
        FogMode fogMode,
        float viewDistance,
        boolean thickFog,
        float tickDelta,
        CallbackInfo ci
    ) {
        var vrs = IGetVoxyRenderSystem.getNullable();
        if (vrs == null) return;

        // Leave fluid fog (underwater/lava/powder snow) completely alone
        if (camera.getFluidInCamera() != FogType.NONE) return;

        // Adjust sky fog so it always looks smooth and doesn't change with render distance,
        // but leave dense effect fog (e.g. blindness) untouched so the sky also goes dark
        if (fogMode == FogMode.FOG_SKY) {
            if (RenderSystem.getShaderFogEnd() >= 10.0f) {
                RenderSystem.setShaderFogStart(0);
                RenderSystem.setShaderFogEnd(VoxyConfig.CONFIG.skyFogDistance);
            }
            return;
        }

        if (fogMode != FogMode.FOG_TERRAIN) return;

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();

        // Dense effect fog (blindness ~5 blocks, darkness up to ~15 blocks) must be captured as-is
        // so Voxy's own fog pass reproduces it for the LOD, and vanilla fog must stay enabled so the
        // loaded chunks keep the effect. Normal long range fog is still overridden to the LOD distance.
        boolean effectFog = fogEnd < 16.0f;

        float capturedFogEnd = effectFog
            ? fogEnd
            : VoxyConfig.CONFIG.sectionRenderDistance * 32 * 16;

        vrs.setCapturedFog(fogStart, capturedFogEnd, RenderSystem.getShaderFogColor());

        // Always hide vanilla terrain fog - either replaced by voxy or disabled completely.
        // Never disable it for dense effect fog or the vanilla chunks would lose it.
        if (!effectFog) {
            RenderSystem.setShaderFogStart(999999999);
            RenderSystem.setShaderFogEnd(999999999);
        }
    }
}
