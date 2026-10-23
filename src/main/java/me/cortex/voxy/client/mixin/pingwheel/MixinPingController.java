package me.cortex.voxy.client.mixin.pingwheel;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.compat.pingwheel.PingWheelBridge;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.PingController;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PingController.class, remap = false)
public abstract class MixinPingController {
    @Shadow @Final private static ClientConfig CLIENT_CONFIG;
    @Shadow private static int pingSequence;
    @Unique private static long voxy$token;
    @Unique private static boolean voxy$taken;

    @Inject(method = "performPingAction(F)V", at = @At("HEAD"))
    private static void voxy$begin(float partialTick, CallbackInfo ci) {
        voxy$token = PingWheelBridge.beginOperation();
        voxy$taken = false;
    }

    @WrapOperation(method = "performPingAction(F)V", at = @At(value = "INVOKE",
            target = "Lnx/pingwheel/common/math/Raycast;traceDirectional(Lnet/minecraft/world/phys/Vec3;FDZ)Lnet/minecraft/world/phys/HitResult;"))
    private static HitResult voxy$normalPick(Vec3 direction, float partialTick, double distance,
                                           boolean crouching, Operation<HitResult> original) {
        HitResult hit = original.call(direction, partialTick, distance, crouching);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            voxy$taken = PingWheelBridge.tryPick(voxy$token, direction, partialTick, distance, crouching,
                    CLIENT_CONFIG, pingSequence);
        }
        return hit;
    }

    // This field is read only in the original MISS branch, before starting DH.
    @Inject(method = "performPingAction(F)V", at = @At(value = "FIELD",
            target = "Lnx/pingwheel/common/integration/ModContext;HasDistantHorizons:Z"), cancellable = true)
    private static void voxy$takeMiss(float partialTick, CallbackInfo ci) {
        if (voxy$taken) ci.cancel();
    }
}
