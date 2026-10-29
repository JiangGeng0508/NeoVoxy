package me.cortex.voxy.client.mixin.seedgen;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SeedGen only publishes solid stacks; NeoVoxy also persists supplied empty space. */
@Pseudo
@Mixin(targets = "mobius.voxyseedgen.voxy.LadderWriter", remap = false)
public abstract class MixinSeedGenLadderWriter {
    @Shadow @Final private WorldSection[] heldSections;
    @Shadow @Final private boolean[] heldSolid;

    @Shadow private void markDirty(WorldSection section, int type, int faces) {
        throw new AssertionError();
    }

    @Inject(method = "release", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/common/world/WorldSection;release()I"), require = 1)
    private void voxy$publishEmptyStack(int slot, CallbackInfo ci) {
        if (!this.heldSolid[slot]) {
            // Use SeedGen's own backpressure and publication path before it drops the reference.
            // Air may clear existing terrain, so invalidate every neighbouring face as well.
            this.markDirty(this.heldSections[slot], WorldEngine.UPDATE_TYPE_BLOCK_BIT, 63);
        }
    }
}
