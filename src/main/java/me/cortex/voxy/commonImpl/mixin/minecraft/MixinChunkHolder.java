package me.cortex.voxy.commonImpl.mixin.minecraft;

import me.cortex.voxy.commonImpl.compat.sable.SableParentChunkLightSync;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder {
    @Shadow @Final private LevelHeightAccessor levelHeightAccessor;

    //getPos() is declared on GenerationChunkHolder, the superclass - a @Shadow for it fails to attach
    //(@Shadow resolves only members the target class itself declares). It is public, so the inherited
    //method is reachable through a plain cast.
    @Inject(method = "blockChanged", at = @At("HEAD"))
    private void voxy$markBlockDirty(BlockPos pos, CallbackInfo ci) {
        if (this.levelHeightAccessor instanceof ServerLevel level) {
            SableParentChunkLightSync.markDirty(level, ((ChunkHolder) (Object) this).getPos());
        }
    }

    @Inject(method = "sectionLightChanged", at = @At("HEAD"))
    private void voxy$markLightDirty(LightLayer layer, int sectionY, CallbackInfo ci) {
        if (this.levelHeightAccessor instanceof ServerLevel level) {
            SableParentChunkLightSync.markDirty(level, ((ChunkHolder) (Object) this).getPos());
        }
    }
}


