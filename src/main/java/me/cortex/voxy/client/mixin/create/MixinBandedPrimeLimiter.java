package me.cortex.voxy.client.mixin.create;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.engine_room.flywheel.impl.visual.BandedPrimeLimiter", remap = false)
public class MixinBandedPrimeLimiter {
    @Inject(method = "shouldUpdate", at = @At("HEAD"), cancellable = true)
    private void voxy$fullRateOnShips(double distanceSquared, CallbackInfoReturnable<Boolean> cir) {
        if (distanceSquared > 1.0e13) {
            cir.setReturnValue(true);
        }
    }
}
