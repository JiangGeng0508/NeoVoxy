package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import me.cortex.voxy.client.compat.ShipBorne;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcherShip {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private <E extends Entity> void voxy$forceShipContraptions(E entity, Frustum frustum, double camX, double camY, double camZ,
                                                               CallbackInfoReturnable<Boolean> cir) {
        //getContraption() != null mirrors the precondition Create's own shouldRender override applies -
        //forcing the gate open before the contraption NBT has synced would just run an empty render
        if (entity instanceof AbstractContraptionEntity contraption
                && contraption.getContraption() != null
                && ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            //EntityCulling cancels renderEntity a layer below this gate, and its ray test against the
            //plot-grid position always says occluded - clear its flag (arms EC's own forced-visible
            //timeout) so the pass we just opened actually draws
            me.cortex.voxy.client.compat.create.NowheelCulled.uncull(entity);
            cir.setReturnValue(true);
        }
    }
}
