package me.cortex.voxy.client.mixin.aeronautics;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer", remap = false)
public abstract class MixinClientBalloonEffectRenderer {
    @ModifyArgs(
            method = "renderBalloonEffects",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;polygonOffset(FF)V", remap = true),
            require = 0
    )
    private static void voxy$keepHotAirBehindBalloon(Args args) {
        float factor = args.get(0);
        float units = args.get(1);
        if (IGetVoxyRenderSystem.getNullable() != null && (factor < 0.0f || units < 0.0f)) {
            args.set(0, 0.0f);
            args.set(1, 1.0f);
        }
    }
}
