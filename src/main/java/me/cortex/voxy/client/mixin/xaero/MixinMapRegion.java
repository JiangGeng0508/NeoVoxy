package me.cortex.voxy.client.mixin.xaero;

import me.cortex.voxy.client.compat.xaero.SupplementedRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import xaero.map.region.MapRegion;

@Mixin(value = MapRegion.class, remap = false)
public class MixinMapRegion implements SupplementedRegion {
    @Unique private boolean voxy$supplement;
    public boolean voxy$hasSupplement() { return this.voxy$supplement; }
    public void voxy$setSupplement(boolean present) { this.voxy$supplement = present; }
}
