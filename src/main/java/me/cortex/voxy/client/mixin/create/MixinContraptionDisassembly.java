package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionDisassemblyPacket;
import me.cortex.voxy.client.compat.create.DistantContraptionManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContraptionEntity.class)
public class MixinContraptionDisassembly {
    @Inject(method = "handleDisassemblyPacket", at = @At("HEAD"))
    private static void voxy$dropSnapshotOnDisassembly(ContraptionDisassemblyPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        var entity = level.getEntity(packet.entityId());
        if (entity == null) {
            return;
        }
        if (level.isLoaded(entity.blockPosition())) {
            DistantContraptionManager.removeDead(entity.getUUID());
            if (entity instanceof AbstractContraptionEntity ce) {
                var contraption = ce.getContraption();
                var box = contraption != null && contraption.bounds != null
                        ? contraption.bounds.move(ce.getX(), ce.getY(), ce.getZ()).inflate(1.0)
                        : ce.getBoundingBox().inflate(1.0);
                me.cortex.voxy.client.compat.create.SectionReingestQueue.scheduleBox(level, box, 5);
            }
        } else if (entity instanceof AbstractContraptionEntity ce) {
            DistantContraptionManager.retireToLeaveBehind(ce);
        }
    }
}
