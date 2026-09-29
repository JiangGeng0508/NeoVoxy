package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.client.Minecraft;

public final class SableShipContent {
    private SableShipContent() {}

    private static java.lang.ref.WeakReference<net.minecraft.client.multiplayer.ClientLevel> cachedLevel = new java.lang.ref.WeakReference<>(null);
    private static ClientSubLevelContainer cachedContainer;

    private static ClientSubLevelContainer container() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            cachedLevel = new java.lang.ref.WeakReference<>(null);
            cachedContainer = null;
            return null;
        }
        if (cachedLevel.get() != level) {
            cachedLevel = new java.lang.ref.WeakReference<>(level);
            cachedContainer = SubLevelContainer.getContainer(level);
        }
        return cachedContainer;
    }

    public static boolean inSubLevel(int chunkX, int chunkZ) {
        ClientSubLevelContainer container = container();
        return container != null && container.inBounds(chunkX, chunkZ);
    }

    public static boolean hasAnyShip() {
        ClientSubLevelContainer container = container();
        return container != null && !container.getAllSubLevels().isEmpty();
    }

    /**
     * Screen extent of every ship in the level. A pass that exists only to let LOD occlude ship content
     * needs no more than this - and nothing at all when the answer is a skip.
     */
    public static SableScreenBounds.Result shipScreenBounds(double cameraX, double cameraY, double cameraZ,
                                                            org.joml.Matrix4f modelView, org.joml.Matrix4f projection,
                                                            double overhangBlocks) {
        ClientSubLevelContainer container = container();
        if (container == null) {
            return SableScreenBounds.Result.allNear();
        }
        return SableScreenBounds.of(container.getAllSubLevels(), cameraX, cameraY, cameraZ,
                modelView, projection, overhangBlocks);
    }

    //Diagnostics surfaced by /voxy debug ship
    public static volatile long ensureCalls;
    public static volatile long ensureRegistered;

    public static void ensureFlywheelState(net.minecraft.world.entity.Entity entity) {
        var level = entity.level();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return;
        }
        ensureCalls++;
        int plotX = (cp.x >> container.getLogPlotSize()) - container.getOrigin().x;
        int plotZ = (cp.z >> container.getLogPlotSize()) - container.getOrigin().y;
        if (dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.getInfo(net.minecraft.world.level.ChunkPos.asLong(plotX, plotZ)) != null) {
            return;
        }
        var subLevel = container.getSubLevel(plotX, plotZ);
        if (subLevel != null) {
            dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.createRenderInfo(level, subLevel);
            ensureRegistered++;
        }
    }

    //Diagnostic twin of ensureFlywheelState for /voxy debug ship: is sable's per-plot Flywheel render
    //state present for the plot this entity sits in?
    public static String flywheelStateStatus(net.minecraft.world.entity.Entity entity) {
        SubLevelContainer container = SubLevelContainer.getContainer(entity.level());
        if (container == null) {
            return "no container";
        }
        var cp = entity.chunkPosition();
        if (!container.inBounds(cp.x, cp.z)) {
            return "not in plot bounds";
        }
        int plotX = (cp.x >> container.getLogPlotSize()) - container.getOrigin().x;
        int plotZ = (cp.z >> container.getLogPlotSize()) - container.getOrigin().y;
        return dev.ryanhcode.sable.neoforge.compatibility.flywheel.FlywheelCompatNeoForge.getInfo(net.minecraft.world.level.ChunkPos.asLong(plotX, plotZ)) != null ? "ok" : "NULL";
    }

}
