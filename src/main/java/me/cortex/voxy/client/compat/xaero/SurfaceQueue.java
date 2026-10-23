package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.common.world.WorldEngine;
import java.util.BitSet;
import java.util.LinkedHashMap;

/** Key-only, bounded FIFO of horizontal 512-block regions. A coarse section adds at
 * most 32 bit ranges; no voxel reads, IO or section references on producer threads. */
public final class SurfaceQueue {
    public record Tile(int x, int z) {}
    private final int capacity;
    private final LinkedHashMap<Long, BitSet> regions = new LinkedHashMap<>();
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
        BitSet pending = this.regions.get(region);
        if (pending == null) {
            if (this.regions.size() == this.capacity) {
                this.regions.pollFirstEntry();
                this.overflow++;
            }
            this.regions.put(region, pending = new BitSet(1024));
        }
        for (int z = startZ & 31; z < (startZ & 31) + side; z++) {
            int from = z * 32 + (startX & 31);
            pending.set(from, from + side);
        }
    }

    public synchronized Tile poll() {
        var entry = this.regions.firstEntry();
        if (entry == null) return null;
        int index = entry.getValue().nextSetBit(0);
        entry.getValue().clear(index);
        this.regions.remove(entry.getKey());
        if (!entry.getValue().isEmpty()) this.regions.put(entry.getKey(), entry.getValue());
        return new Tile(((int) (entry.getKey() >> 32) << 5) + (index & 31),
                ((int) (long) entry.getKey() << 5) + (index >> 5));
    }

    public synchronized int regionCount() { return this.regions.size(); }
    public synchronized long overflowCount() { return this.overflow; }
    public synchronized void clear() { this.regions.clear(); }
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
}
