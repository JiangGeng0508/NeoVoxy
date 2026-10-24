package me.cortex.voxy.client.core.picking;

/** Blocks throughout; LOD picking follows the render radius independently of the ordinary ray. */
public final class PickingDistance {
    public static double limit(float radius, int capBlocks) {
        if (!Float.isFinite(radius) || radius <= 0) return 0;
        double distance = radius * 512.0;
        return capBlocks > 0 ? Math.min(distance, capBlocks) : distance;
    }
    private PickingDistance() {}
}
