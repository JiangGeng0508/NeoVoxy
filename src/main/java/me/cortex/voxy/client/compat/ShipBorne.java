package me.cortex.voxy.client.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;

public final class ShipBorne {
    private static final boolean SABLE_PRESENT = ModList.get() != null && ModList.get().isLoaded("sable");
    private static volatile boolean gateUnavailable;
    private static volatile boolean healUnavailable;

    private ShipBorne() {}

    public static boolean isShipBorne(double x, double z) {
        return inSubLevel(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    public static boolean isShipBorne(BlockPos pos) {
        return inSubLevel(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public static boolean anyShipPresent() {
        if (!SABLE_PRESENT || gateUnavailable) {
            return false;
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.hasAnyShip();
        } catch (LinkageError | RuntimeException e) {
            gateUnavailable = true;
            return false;
        }
    }

    public static me.cortex.voxy.client.compat.sable.SableScreenBounds.Result shipScreenBounds(
            double cameraX, double cameraY, double cameraZ,
            org.joml.Matrix4f modelView, org.joml.Matrix4f projection, double overhangBlocks) {
        if (!SABLE_PRESENT || gateUnavailable) {
            return me.cortex.voxy.client.compat.sable.SableScreenBounds.Result.allNear();
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.shipScreenBounds(
                    cameraX, cameraY, cameraZ, modelView, projection, overhangBlocks);
        } catch (LinkageError | RuntimeException e) {
            gateUnavailable = true;
            return me.cortex.voxy.client.compat.sable.SableScreenBounds.Result.allNear();
        }
    }

    //Self-heal for sable's join-time-only Flywheel plot registration (see SableShipContent) - safe to
    //call every frame, no-ops once the state exists
    public static void ensureShipFlywheelState(net.minecraft.world.entity.Entity entity) {
        if (!SABLE_PRESENT || healUnavailable) {
            return;
        }
        try {
            me.cortex.voxy.client.compat.sable.SableShipContent.ensureFlywheelState(entity);
        } catch (LinkageError | RuntimeException e) {
            //Only the self-heal goes; sable still registers its own plots at join time, so what is lost
            //is the gap-filling for plots that were not known then - a cosmetic degradation next to
            //losing the gate.
            healUnavailable = true;
        }
    }

    private static boolean inSubLevel(int chunkX, int chunkZ) {
        if (!SABLE_PRESENT || gateUnavailable) {
            return false;
        }
        try {
            return me.cortex.voxy.client.compat.sable.SableShipContent.inSubLevel(chunkX, chunkZ);
        } catch (LinkageError | RuntimeException e) {
            gateUnavailable = true;
            return false;
        }
    }
}
