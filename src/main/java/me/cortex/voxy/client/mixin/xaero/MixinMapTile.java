package me.cortex.voxy.client.mixin.xaero;

import me.cortex.voxy.client.compat.xaero.ProvenancedPixel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.MapBlock;
import xaero.map.region.MapTile;

@Mixin(value = MapTile.class, remap = false)
public abstract class MixinMapTile {
    @Shadow public abstract MapBlock getBlock(int x, int z);

    // MapWriter calls this after a genuine full-chunk scan, even if its pixels
    // compared equal to our existing pixels. Equality must not retain ownership.
    @Inject(method = "setWrittenOnce", at = @At("RETURN"))
    private void voxy$realChunkWins(boolean written, CallbackInfo ci) {
        if (written) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            MapBlock block = this.getBlock(x, z);
            if (block != null) ((ProvenancedPixel) block).voxy$setSource(0);
        }
    }
}
