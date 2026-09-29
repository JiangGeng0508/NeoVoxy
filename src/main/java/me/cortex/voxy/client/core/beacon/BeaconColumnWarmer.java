package me.cortex.voxy.client.core.beacon;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.BlockPos;

/** Loads a cold beacon column off the render thread, then schedules one cache-only retry. */
public final class BeaconColumnWarmer {
    private static final LongOpenHashSet WARMING = new LongOpenHashSet();
    private static final java.util.concurrent.ExecutorService WARM_POOL =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "Voxy beacon column warmup");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private BeaconColumnWarmer() {}

    public static void warm(WorldEngine engine, int bx, int by, int bz, int walkTop) {
        long beaconPos = BlockPos.asLong(bx, by, bz);
        synchronized (WARMING) {
            if (!WARMING.add(beaconPos)) {
                return;
            }
        }
        try {
            engine.acquireRef();
        } catch (RuntimeException e) {
            synchronized (WARMING) {
                WARMING.remove(beaconPos);
            }
            return;
        }
        WARM_POOL.execute(() -> {
            try {
                if (engine.isLive()) {
                    int baseY = (by - 1) >> 5;
                    for (int sx = (bx - 1) >> 5; sx <= (bx + 1) >> 5; sx++) {
                        for (int sz = (bz - 1) >> 5; sz <= (bz + 1) >> 5; sz++) {
                            touch(engine, sx, baseY, sz);
                        }
                    }
                    for (int sy = (by + 1) >> 5; sy <= walkTop >> 5; sy++) {
                        touch(engine, bx >> 5, sy, bz >> 5);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    engine.releaseRef();
                } catch (RuntimeException ignored) {
                }
                synchronized (WARMING) {
                    WARMING.remove(beaconPos);
                }
                BeaconBeamTracker.queueDirty(beaconPos);
            }
        });
    }

    private static void touch(WorldEngine engine, int sx, int sy, int sz) {
        var section = engine.acquireIfExists(0, sx, sy, sz);
        if (section != null) {
            section.release();
        }
    }
}
