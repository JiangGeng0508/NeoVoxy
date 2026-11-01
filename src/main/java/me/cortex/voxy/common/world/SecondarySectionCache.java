package me.cortex.voxy.common.world;

import java.util.LinkedHashMap;

/** One instance-wide LRU. Lock order is tracker shard -> this cache, never the reverse. */
public final class SecondarySectionCache {
    private record Key(ActiveSectionTracker tracker, long position) {}
    public record Stats(int entries, long voxelBytes, int maxEntries, long maxBytes, long evictions) {}
    private final LinkedHashMap<Key, WorldSection> entries = new LinkedHashMap<>();
    private final int maxEntries;
    private final long maxBytes;
    private long bytes, evictions;

    public static long defaultByteLimit(long maxHeap) {
        return Math.clamp(maxHeap / 16, 64L << 20, 256L << 20);
    }

    public static int defaultEntryLimit(long maxHeap) {
        return maxHeap >= (1L << 32) - (200L << 20) ? 2048 : 1024;
    }

    public SecondarySectionCache() {
        this(defaultEntryLimit(Runtime.getRuntime().maxMemory()), defaultByteLimit(Runtime.getRuntime().maxMemory()));
    }

    public SecondarySectionCache(int maxEntries, long maxBytes) {
        if (maxEntries < 0 || maxBytes < 0) throw new IllegalArgumentException("Negative cache budget");
        this.maxEntries = maxEntries; this.maxBytes = maxBytes;
    }

    private static long bytes(WorldSection section) {
        long[] data = section._rawOrNull();
        return data == null ? 0 : (long) data.length * Long.BYTES;
    }

    synchronized void put(WorldSection section) {
        if (!section.isFreed() || section.getRefCount() != 0 || section.isDirty || section.inSaveQueue) {
            throw new IllegalStateException("Only unreferenced, saved sections may enter the secondary cache");
        }
        if (this.maxEntries == 0 || bytes(section) > this.maxBytes) {
            this.evictions++; section._releaseArray(); return;
        }
        if (this.entries.putIfAbsent(new Key(section.tracker, section.key), section) != null) {
            throw new IllegalStateException("Duplicate secondary section");
        }
        this.bytes += bytes(section);
        while (this.entries.size() > this.maxEntries || this.bytes > this.maxBytes) this.evictOldest();
    }

    synchronized WorldSection take(ActiveSectionTracker tracker, long key) {
        var section = this.entries.remove(new Key(tracker, key));
        if (section != null) this.bytes -= bytes(section);
        return section;
    }

    private void evictOldest() {
        var section = this.entries.pollFirstEntry().getValue();
        this.bytes -= bytes(section); this.evictions++;
        section._releaseArray();
    }

    synchronized boolean contains(ActiveSectionTracker tracker, long key) {
        return this.entries.containsKey(new Key(tracker, key));
    }

    synchronized int size(ActiveSectionTracker tracker) {
        return (int) this.entries.keySet().stream().filter(key -> key.tracker == tracker).count();
    }

    synchronized long[] keys(ActiveSectionTracker tracker) {
        return this.entries.keySet().stream().filter(key -> key.tracker == tracker).mapToLong(Key::position).toArray();
    }

    synchronized void clear(ActiveSectionTracker tracker) {
        var it = this.entries.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getKey().tracker != tracker) continue;
            var section = entry.getValue(); it.remove();
            this.bytes -= bytes(section); section._releaseArray();
        }
    }

    public synchronized Stats stats() { return new Stats(this.entries.size(), this.bytes, this.maxEntries, this.maxBytes, this.evictions); }
}
