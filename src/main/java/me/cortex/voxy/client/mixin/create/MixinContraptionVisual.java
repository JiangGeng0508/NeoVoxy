package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ContraptionVisual.class)
public abstract class MixinContraptionVisual {
    @Shadow @Final protected VisualEmbedding embedding;

    private static final Matrix4f VOXY$ZERO_POSE = new Matrix4f().scaling(0.0f);
    private static final Matrix3f VOXY$ZERO_NORMAL = new Matrix3f().scaling(0.0f);

    @Inject(method = "beginFrame", at = @At("TAIL"))
    private void voxy$cullBeyondRenderDistance(DynamicVisual.Context ctx, CallbackInfo ci) {
        //Train has its own mixin (fires via super.beginFrame); skip here to avoid double work
        if ((Object) this instanceof CarriageContraptionVisual) {
            return;
        }
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || this.embedding == null) {
            return;
        }
        Entity entity = ((AccessorAbstractEntityVisual) this).voxy$getEntity();
        //Riding a sable ship the entity sits at plot-grid coordinates, where a world-space distance is
        //meaningless - leave it to sable
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(entity.getX(), entity.getZ())) {
            me.cortex.voxy.client.compat.ShipBorne.ensureShipFlywheelState(entity);
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        if (entity.position().distanceToSqr(cam) > reach * reach) {
            this.embedding.transforms(VOXY$ZERO_POSE, VOXY$ZERO_NORMAL);
        }
    }
}
