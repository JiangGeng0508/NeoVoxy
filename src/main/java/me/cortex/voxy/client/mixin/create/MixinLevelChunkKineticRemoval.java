package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import me.cortex.voxy.client.compat.create.KineticSnapshots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class MixinLevelChunkKineticRemoval {
    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void voxy$dropSnapshotWithBlock(BlockPos pos, CallbackInfo ci) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (self.getLevel().isClientSide()
                && self.getBlockEntity(pos) instanceof KineticBlockEntity) {
            KineticSnapshots.queueRemove(pos);
        }
    }
}


