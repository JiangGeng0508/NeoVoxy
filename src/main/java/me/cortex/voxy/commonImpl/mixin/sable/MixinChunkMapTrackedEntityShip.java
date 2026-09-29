package me.cortex.voxy.commonImpl.mixin.sable;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import me.cortex.voxy.commonImpl.compat.sable.ShipBorneServer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class MixinChunkMapTrackedEntityShip {
    @Shadow @Final Entity entity;

    @Redirect(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"), require = 0)
    private int voxy$shipContraptionTrackingRange(int range, int viewDistanceBlocks) {
        if (this.entity instanceof AbstractContraptionEntity) {
            int shipRange = ShipBorneServer.shipTrackingRangeBlocks(this.entity);
            if (shipRange > 0) {
                return Math.max(shipRange, Math.min(range, viewDistanceBlocks));
            }
        }
        return Math.min(range, viewDistanceBlocks);
    }
}
