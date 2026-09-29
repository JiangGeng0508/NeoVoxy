package me.cortex.voxy.commonImpl.compat.sable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

public final class ShipBorneServer {
    private static final boolean SABLE_PRESENT = ModList.get() != null && ModList.get().isLoaded("sable");
    private static boolean unavailable;

    private ShipBorneServer() {}

    //Blocks radius sable tracks the containing ship at, or -1 when the entity is not in a plot (or
    //sable is unusable)
    public static int shipTrackingRangeBlocks(Entity entity) {
        if (!SABLE_PRESENT || unavailable) {
            return -1;
        }
        try {
            return shipTrackingRangeBlocks0(entity);
        } catch (LinkageError | RuntimeException e) {
            unavailable = true;
            return -1;
        }
    }

    private static int shipTrackingRangeBlocks0(Entity entity) {
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(entity.level());
        if (container == null) {
            return -1;
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return -1;
        }
        if (entity.level() instanceof ServerLevel serverLevel) {
            double hullRange = SableContraptionRenderDistance.getRangeBlocks(serverLevel);
            if (hullRange > 0.0) {
                return (int) hullRange;
            }
        }
        //Zero means the distant-contraption integration is off, and shouldLoad falls through to sable's
        //own check - so the hull is back on this range and the entity follows it there as well.
        return (int) dev.ryanhcode.sable.SableConfig.SUB_LEVEL_TRACKING_RANGE.getAsDouble();
    }
}
