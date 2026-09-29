package me.cortex.voxy.client.compat.create;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import me.cortex.voxy.client.compat.ShipBorne;
import me.cortex.voxy.client.config.VoxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

public final class KineticCull {
    private KineticCull() {}

    //Constant consumers so the per-frame collectCrumblingInstances walk never allocates.
    private static final Consumer<Instance> HIDE = instance -> {
        if (instance != null) {
            instance.setVisible(false);
        }
    };
    private static final Consumer<Instance> SHOW = instance -> {
        if (instance != null) {
            instance.setVisible(true);
            //A block update may have re-pushed rotation params while the instance was hidden; mark it so
            //the reveal reuploads the current state.
            instance.setChanged();
        }
    };

    static volatile double cachedReachSq = -1;

    private static double reachSq() {
        double cached = cachedReachSq;
        if (cached >= 0) {
            return cached;
        }
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return reach * reach;
    }

    private static boolean beyond(BlockPos pos, double camX, double camY, double camZ) {
        double dx = pos.getX() + 0.5 - camX;
        double dy = pos.getY() + 0.5 - camY;
        double dz = pos.getZ() + 0.5 - camZ;
        return (dx * dx + dy * dy + dz * dz) > reachSq();
    }

    //Flywheel visual path: the camera comes from the frame context.
    public static boolean beyond(BlockPos pos, DynamicVisual.Context ctx,
                                 net.minecraft.world.level.Level visualLevel) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics) {
            return false;
        }
        if (visualLevel != Minecraft.getInstance().level) {
            return false;
        }
        if (ShipBorne.isShipBorne(pos)) {
            return false;
        }
        Vec3 cam = ctx.camera().getPosition();
        return beyond(pos, cam.x, cam.y, cam.z);
    }

    //Vanilla-BER fallback path (Flywheel backend off): the camera comes from the game renderer.
    public static boolean beyondForRender(BlockPos pos) {
        var cfg = VoxyConfig.CONFIG;
        if (!cfg.isRenderingEnabled() || !cfg.distantKinetics || ShipBorne.isShipBorne(pos)) {
            return false;
        }
        var mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return false;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double dx = pos.getX() + 0.5 - cam.x;
        double dy = pos.getY() + 0.5 - cam.y;
        double dz = pos.getZ() + 0.5 - cam.z;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > reachSq()) {
            return true;
        }
        double reach = Math.sqrt(reachSq());
        double inner = Math.max(0, reach - 16.0);
        if (distSq <= inner * inner) {
            return false;
        }
        double cx = (pos.getX() & ~15) + 8 - cam.x;
        double cy = (pos.getY() & ~15) + 8 - cam.y;
        double cz = (pos.getZ() & ~15) + 8 - cam.z;
        double gate = Math.max(0, reach - 14.0);
        return cx * cx + cy * cy + cz * cz >= gate * gate && KineticSnapshots.drawsSnapAt(pos);
    }

    public static void hide(BlockEntityVisual visual) {
        visual.collectCrumblingInstances(HIDE);
        AzimuthBehaviourIndex.apply(visual, HIDE);
    }

    public static void show(BlockEntityVisual visual) {
        visual.collectCrumblingInstances(SHOW);
        AzimuthBehaviourIndex.apply(visual, SHOW);
    }

    public static boolean enclosed(BlockPos pos) {
        if (!VoxyConfig.CONFIG.kineticEnclosedCulling) {
            return false;
        }
        var level = Minecraft.getInstance().level;
        if (level == null || me.cortex.voxy.client.compat.ShipBorne.isShipBorne(pos)) {
            return false;
        }
        var state = level.getBlockState(pos);
        var block = state.getBlock();
        if (block instanceof com.simibubi.create.content.kinetics.simpleRelays.ICogWheel) {
            return false;
        }
        if (block instanceof com.simibubi.create.content.decoration.encasing.EncasedBlock
                && block instanceof com.simibubi.create.content.kinetics.base.IRotate rotate) {
            var axis = rotate.getRotationAxis(state);
            return opaqueNeighbor(level, pos.relative(net.minecraft.core.Direction.get(
                            net.minecraft.core.Direction.AxisDirection.POSITIVE, axis)))
                    && opaqueNeighbor(level, pos.relative(net.minecraft.core.Direction.get(
                            net.minecraft.core.Direction.AxisDirection.NEGATIVE, axis)));
        }
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (!opaqueNeighbor(level, pos.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private static boolean opaqueNeighbor(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.getBlockState(pos).isSolidRender(level, pos);
    }
}
