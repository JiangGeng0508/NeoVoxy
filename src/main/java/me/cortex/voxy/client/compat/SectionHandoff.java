package me.cortex.voxy.client.compat;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.LodBoundaryFade;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;

/** Hands static models over only to sections in Sodium's current terrain draw set. */
public final class SectionHandoff {
    private static long frameId;
    private static long frameTimeNanos;

    private SectionHandoff() {}

    public static void beginFrame() {
        frameId++;
        frameTimeNanos = System.nanoTime();
    }

    public static long frameId() { return frameId; }

    public static long frameTimeNanos() { return frameTimeNanos; }

    public static double distanceSquared() {
        var boundary = LodBoundaryFade.getDistances();
        double distance = boundary.enabled() ? boundary.fadeStart()
                : Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return distance * distance;
    }

    public static boolean insideBoundary(double centerX, double centerZ, double halfExtent, double distanceSq) {
        // Use the farthest horizontal corner, including models that overhang their section.
        double farX = Math.abs(centerX) + halfExtent;
        double farZ = Math.abs(centerZ) + halfExtent;
        return farX * farX + farZ * farZ < distanceSq;
    }

    public static boolean vanillaOwns(int sectionX, int sectionY, int sectionZ) {
        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer == null || VoxyClient.disableSodiumChunkRender()) return false;
        var system = ((IGetVoxyRenderSystem) renderer).voxy$getRenderSystem();
        return system != null && system.chunkBoundRenderer.containsVisibleSection(
                SectionPos.asLong(sectionX, sectionY, sectionZ));
    }
}
