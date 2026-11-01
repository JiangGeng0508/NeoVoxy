package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.world.WorldEngine;

/** Horizontal section bounds, shared by client admission and server dispatch. Units are blocks. */
public final class LodViewDistance {
    public static final int MIN_BLOCKS = 320;
    public static final int MAX_BLOCKS = 32768;

    private LodViewDistance() {}

    public static int clamp(int blocks) { return Math.clamp(blocks, MIN_BLOCKS, MAX_BLOCKS); }

    public static boolean contains(long section, double x, double z, int radius) {
        long width = 32L << WorldEngine.getLevel(section);
        long x0 = (long) WorldEngine.getX(section) * width;
        long z0 = (long) WorldEngine.getZ(section) * width;
        double dx = Math.max(0, Math.max(x0 - x, x - (x0 + width)));
        double dz = Math.max(0, Math.max(z0 - z, z - (z0 + width)));
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
