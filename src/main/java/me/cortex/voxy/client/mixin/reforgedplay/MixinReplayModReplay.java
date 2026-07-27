package me.cortex.voxy.client.mixin.reforgedplay;

import me.cortex.voxy.client.compat.ReForgedPlayCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.replaymod.replay.ReplayModReplay", remap = false)
public class MixinReplayModReplay {
    @Inject(
            method = "startReplay(Lcom/replaymod/replaystudio/replay/ReplayFile;ZZ)Lcom/replaymod/replay/ReplayHandler;",
            at = @At("HEAD"),
            remap = false
    )
    private void voxy$cacheReplayFile(@Coerce Object replayFile, boolean checkModCompat, boolean asyncMode, CallbackInfoReturnable<?> cir) {
        ReForgedPlayCompat.beginReplay(replayFile);
    }

    @Inject(method = "forcefullyStopReplay", at = @At("HEAD"), remap = false)
    private void voxy$clearReplayFile(CallbackInfo ci) {
        ReForgedPlayCompat.endReplay();
    }
}
