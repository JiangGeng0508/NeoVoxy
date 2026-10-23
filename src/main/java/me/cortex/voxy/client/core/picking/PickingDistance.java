package me.cortex.voxy.client.core.picking;

/** Blocks throughout; the original PingWheel ray remains the absolute upper bound. */
public final class PickingDistance {
    public static double limit(double original, float radius, int capBlocks) {
        if (!Double.isFinite(original) || !Float.isFinite(radius) || original <= 0 || radius <= 0) return 0;
        double distance = Math.min(original, radius * 512.0);
        return capBlocks > 0 ? Math.min(distance, capBlocks) : distance;
    }
    private PickingDistance() {}
}
