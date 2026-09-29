package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.trains.bogey.BogeyVisual;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CarriageContraptionVisual.class)
public abstract class MixinCarriageContraptionVisual {
    @Shadow @org.spongepowered.asm.mixin.Final private BogeyVisual[] visuals;

    private static final Matrix4f VOXY$ZERO_POSE = new Matrix4f().scaling(0.0f);
    private static final Matrix3f VOXY$ZERO_NORMAL = new Matrix3f().scaling(0.0f);

    @Inject(method = "beginFrame", at = @At("TAIL"))
    private void voxy$cullBeyondRenderDistance(DynamicVisual.Context ctx, CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return;
        }
        VisualEmbedding embedding = ((AccessorContraptionVisual) this).voxy$getEmbedding();
        if (embedding == null) {
            return;
        }
        Entity entity = ((AccessorAbstractEntityVisual) this).voxy$getEntity();
        //Riding a sable ship the entity sits at plot-grid coordinates, where a world-space distance is
        //meaningless - leave it to sable
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            return;
        }
        Vec3 cam = ctx.camera().getPosition();
        //Yield exactly where the distant train mesh takes over (not the render distance): any gap
        //between the two thresholds is a ring where both draw, and the pose lag between them shows
        if (entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage
                && me.cortex.voxy.client.compat.create.TrainHandover.shouldCullLive(carriage, cam)) {
            //Carriage body (structure + child BEs + actors) draws through the embedding - collapse it
            embedding.transforms(VOXY$ZERO_POSE, VOXY$ZERO_NORMAL);
            //Bogeys draw through the main context, not the embedding - hide them explicitly. This
            //overrides the update() beginFrame just did; next in-range frame it updates them back.
            for (BogeyVisual bogey : this.visuals) {
                if (bogey != null) {
                    bogey.hide();
                }
            }
        }
    }
}
