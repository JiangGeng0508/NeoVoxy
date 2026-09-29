package me.cortex.voxy.commonImpl.compat.create;

public final class DistantTrainConfig {
    private DistantTrainConfig() {}

    //Absolute ceiling the sampler will never exceed regardless of either input.
    public static final double HARD_MAX = 3072;

    //Client (integrated-server host) preference.
    public static volatile boolean clientEnabled = true;
    public static volatile double clientMaxDistance = HARD_MAX;
    public static volatile boolean clientContraptionsEnabled = true;
    public static volatile double clientContraptionMaxDistance = HARD_MAX;

    //Server (dedicated admin) uniform ceiling.
    public static volatile boolean serverEnabled = true;
    public static volatile double serverMaxDistance = HARD_MAX;
    public static volatile int sampleIntervalTicks = 5;

    private static double clampDistance(double blocks) {
        return blocks > 0 ? Math.min(blocks, HARD_MAX) : HARD_MAX;
    }

    public static void updateClientConfig(boolean enabled, double maxDistanceBlocks) {
        clientEnabled = enabled;
        clientMaxDistance = clampDistance(maxDistanceBlocks);
    }

    public static void updateClientContraptionConfig(boolean enabled, double maxDistanceBlocks) {
        clientContraptionsEnabled = enabled;
        clientContraptionMaxDistance = clampDistance(maxDistanceBlocks);
    }

    public static void updateServerConfig(boolean enabled, double maxDistanceBlocks, int intervalTicks) {
        serverEnabled = enabled;
        serverMaxDistance = clampDistance(maxDistanceBlocks);
        sampleIntervalTicks = Math.max(1, intervalTicks);
    }

    //Combined values the sampler reads.
    public static boolean enabled() {
        return clientEnabled && serverEnabled;
    }

    public static double maxDistance() {
        return Math.min(clientMaxDistance, serverMaxDistance);
    }

    public static int sampleInterval() {
        return sampleIntervalTicks;
    }

    public static boolean contraptionsEnabled() {
        return clientContraptionsEnabled && serverEnabled;
    }

    public static double contraptionMaxDistance() {
        return Math.min(clientContraptionMaxDistance, serverMaxDistance);
    }
}
