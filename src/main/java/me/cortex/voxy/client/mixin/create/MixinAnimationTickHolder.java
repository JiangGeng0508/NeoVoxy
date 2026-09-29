package me.cortex.voxy.client.mixin.create;

import me.cortex.voxy.client.compat.create.KineticSnapshots;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AnimationTickHolder.class)
public class MixinAnimationTickHolder {
    @Inject(method = "getRenderTime()F", at = @At("HEAD"), cancellable = true)
    private static void voxy$freezeCaptureClock(CallbackInfoReturnable<Float> cir) {
        if (KineticSnapshots.isCapturingOnThisThread()) {
            cir.setReturnValue(0.0f);
        }
    }

    @Inject(method = "getRenderTime(Lnet/minecraft/world/level/LevelAccessor;)F", at = @At("HEAD"), cancellable = true)
    private static void voxy$freezeCaptureClockForLevel(LevelAccessor level, CallbackInfoReturnable<Float> cir) {
        if (KineticSnapshots.isCapturingOnThisThread()) {
            cir.setReturnValue(0.0f);
        }
    }
}


