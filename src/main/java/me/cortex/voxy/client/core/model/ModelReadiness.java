package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.Logger;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;

/** Publication barrier for CPU model metadata, including shared models and air fallback. */
final class ModelReadiness {
    private static final VarHandle INTS = MethodHandles.arrayElementVarHandle(int[].class);
    private final int[] blocks;
    private final int[] fluids;
    private final CopyOnWriteArrayList<LongConsumer> listeners = new CopyOnWriteArrayList<>();

    ModelReadiness(int[] blocks, int[] fluids) { this.blocks = blocks; this.fluids = fluids; }
    static long blockDependency(int id) { return Integer.toUnsignedLong(id); }
    static long fluidDependency(int id) { return (1L << 32) | Integer.toUnsignedLong(id); }
    int block(int id) { return (int) INTS.getAcquire(blocks, id); }
    int fluid(int id) { return (int) INTS.getAcquire(fluids, id); }
    boolean ready(long dependency) {
        return (dependency >>> 32 == 0 ? block((int) dependency) : fluid((int) dependency)) != -1;
    }
    void publishBlock(int id, int model) {
        INTS.setRelease(blocks, id, model);
        notifyReady(blockDependency(id));
    }
    void publishFluid(int id, int fluid) {
        INTS.setRelease(fluids, id, fluid);
        notifyReady(fluidDependency(id));
    }
    void addListener(LongConsumer listener) { listeners.add(listener); }
    void removeListener(LongConsumer listener) { listeners.remove(listener); }
    private void notifyReady(long dependency) {
        for (var listener : listeners) {
            try { listener.accept(dependency); }
            catch (Exception failure) { Logger.error("Model readiness listener failed", failure); }
        }
    }
}
