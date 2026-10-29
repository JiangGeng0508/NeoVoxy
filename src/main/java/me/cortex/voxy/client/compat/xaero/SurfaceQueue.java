package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.common.world.WorldEngine;
import java.util.BitSet;
import java.util.LinkedHashMap;

/** Key-only, bounded FIFO of horizontal 512-block regions. A coarse section adds at
 * most 32 bit ranges; no voxel reads, IO or section references on producer threads. */
public final class SurfaceQueue {
    public record Tile(int x, int z) {}
    private final int capacity;
    private static final long RETRY_NANOS = 250_000_000L;
    private static final class PendingRegion {
        final BitSet tiles = new BitSet(1024);
        int cursor;
        long retryAt;
        boolean deferred;
    }
    private final LinkedHashMap<Long, PendingRegion> regions = new LinkedHashMap<>();
    private long overflow;

    public SurfaceQueue(int capacity) { this.capacity = Math.max(1, capacity); }

    public synchronized void offer(long section) {
        int lod = WorldEngine.getLevel(section);
        if (lod > WorldEngine.MAX_LOD_LAYER) return;
        int startX = WorldEngine.getX(section) << (lod + 1);
        int startZ = WorldEngine.getZ(section) << (lod + 1);
        int side = 2 << lod;
        // Voxy sections align to map regions at every supported LOD.
        long region = key(startX >> 5, startZ >> 5);
        PendingRegion pending = this.region(region);
        for (int z = startZ & 31; z < (startZ & 31) + side; z++) {
            int from = z * 32 + (startX & 31);
            pending.tiles.set(from, from + side);
        }
    }

    private PendingRegion region(long region) {
        PendingRegion pending = this.regions.get(region);
        if (pending == null) {
            if (this.regions.size() == this.capacity) {
                this.regions.pollFirstEntry();
                this.overflow++;
            }
            this.regions.put(region, pending = new PendingRegion());
        }
        return pending;
    }

    public Tile poll() { return this.poll(System.nanoTime()); }

    synchronized Tile poll(long now) {
        int remaining = this.regions.size();
        while (remaining-- > 0) {
            var entry = this.regions.pollFirstEntry();
            PendingRegion pending = entry.getValue();
            if (pending.deferred && now - pending.retryAt < 0) {
                this.regions.put(entry.getKey(), pending);
                continue;
            }
            pending.deferred = false;
            // Coarse parents are dirtied repeatedly during generation. Starting at
            // bit zero each time would starve the rest of a region indefinitely.
            int index = pending.tiles.nextSetBit(pending.cursor);
            if (index < 0) index = pending.tiles.nextSetBit(0);
            pending.tiles.clear(index);
            pending.cursor = (index + 1) & 1023;
            if (!pending.tiles.isEmpty()) this.regions.put(entry.getKey(), pending);
            return new Tile(((int) (entry.getKey() >> 32) << 5) + (index & 31),
                    ((int) (long) entry.getKey() << 5) + (index >> 5));
        }
        return null;
    }

    /** Re-extract the latest local data after Xaero finishes loading/caching.
     * A busy region must not hold all eight ready slots and block other regions. */
    public void retry(int x, int z) { this.retry(x, z, System.nanoTime()); }

    synchronized void retry(int x, int z, long now) {
        PendingRegion pending = this.region(key(x >> 5, z >> 5));
        pending.tiles.set((z & 31) * 32 + (x & 31));
        pending.retryAt = now + RETRY_NANOS;
        pending.deferred = true;
    }

    public synchronized int regionCount() { return this.regions.size(); }
    public synchronized long overflowCount() { return this.overflow; }
    public synchronized void clear() { this.regions.clear(); }
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
}
