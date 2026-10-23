package me.cortex.voxy.client.compat.xaero;

/** Xaero 1.47.0 / source format 6.8 leaves bits 29..31 and 7 unused.
 * Kept in the pixel itself so native atomic region saves also commit ownership.
 * 0 = real/foreign map, 1..5 = Voxy LOD0..4, 6 = unknown filler.
 * Never infer ownership from block equality or map colours.
 */
public final class SurfaceProvenance {
    public static final int EMPTY = 6;
    public static int encode(int source, boolean provisional) {
        return (source << 29) | (provisional ? 128 : 0);
    }
    public static int source(int bits) { return bits >>> 29; }
    public static boolean provisional(int bits) { return (bits & 128) != 0; }
    public static boolean mayReplace(int previous, int lod, boolean provisional) {
        int source = source(previous);
        if (source == EMPTY) return true;
        if (source < 1 || source > 5 || lod + 1 > source) return false;
        return lod + 1 < source || !provisional || provisional(previous);
    }
    private SurfaceProvenance() {}
}
