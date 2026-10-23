package me.cortex.voxy.client.mixin.xaero;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.cortex.voxy.client.compat.xaero.ProvenancedPixel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.MapBlock;

@Mixin(value = MapBlock.class, remap = false)
public abstract class MixinMapBlock implements ProvenancedPixel {
    @Shadow public abstract int getTopHeight();
    @Unique private int voxy$source;
    @Override public int voxy$getSource() { return this.voxy$source; }
    @Override public void voxy$setSource(int source) { this.voxy$source = source; }

    @Inject(method = "prepareForWriting", at = @At("HEAD"))
    private void voxy$reset(int height, CallbackInfo ci) { this.voxy$source = 0; }

    @ModifyReturnValue(method = "getParametres", at = @At("RETURN"))
    private int voxy$saveSource(int original) {
        // Native 6.8 writes a differing topHeight as one unsigned byte. Preserve
        // its upper four bits for Voxy pixels in unused modern colour-type bits.
        return original | this.voxy$source | (this.voxy$source == 0 ? 0 : ((this.getTopHeight() >> 8) & 15) << 2);
    }
}
