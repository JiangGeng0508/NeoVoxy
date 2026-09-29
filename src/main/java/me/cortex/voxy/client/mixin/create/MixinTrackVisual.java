package me.cortex.voxy.client.mixin.create;

import com.simibubi.create.content.trains.track.TrackVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.SectionTrackedVisual.SectionCollector;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import it.unimi.dsi.fastutil.longs.LongSet;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TrackVisual.class)
public abstract class MixinTrackVisual implements SimpleDynamicVisual {
    @Shadow @org.spongepowered.asm.mixin.Final protected BlockPos pos;
    @Shadow protected SectionCollector lightSections;
    @Shadow public abstract void _delete();
    @Shadow public abstract LongSet collectLightSections();

    @Shadow private void collectConnections() { throw new AssertionError(); }

    private boolean voxy$culled;

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        //Track sitting on a sable ship is at plot-grid coordinates, where a world-space distance is
        //meaningless - leave it to sable
        if (me.cortex.voxy.client.compat.ShipBorne.isShipBorne(this.pos)) {
            return;
        }
        boolean rendering = VoxyConfig.CONFIG.isRenderingEnabled();
        Vec3 cam = ctx.camera().getPosition();
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        double dx = this.pos.getX() + 0.5 - cam.x;
        double dy = this.pos.getY() + 0.5 - cam.y;
        double dz = this.pos.getZ() + 0.5 - cam.z;
        boolean beyond = rendering && (dx * dx + dy * dy + dz * dz) > reach * reach;

        if (beyond) {
            //Idempotent: also clears instances a BE update may have rebuilt while we were far
            this._delete();
            this.voxy$culled = true;
        } else if (this.voxy$culled) {
            this.collectConnections();
            if (this.lightSections != null) {
                this.lightSections.sections(this.collectLightSections());
            }
            this.voxy$culled = false;
        }
    }
}
