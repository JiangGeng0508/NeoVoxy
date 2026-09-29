package me.cortex.voxy.client.mixin.sodiumextra;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.flashyreese.mods.sodiumextra.client.fog.FogDistanceHelper", remap = false)
public abstract class MixinFogDistanceHelper {
    @Inject(method = "expandCylindricalCullDistance(FFFF)F", at = @At("HEAD"), cancellable = true, require = 0)
    private static void voxy$keepVanillaVerticalCull(float currentDistance, float fogStart, float fogEnd,
                                                     float renderDistance, CallbackInfoReturnable<Float> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!IrisUtil.irisShaderPackEnabled()
                && minecraft.levelRenderer instanceof IGetVoxyRenderSystem owner
                && owner.voxy$getRenderSystem() != null) {
            cir.setReturnValue(currentDistance);
        }
    }
}
