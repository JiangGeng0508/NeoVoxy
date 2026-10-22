package me.cortex.voxy.commonImpl.network;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/** Bounded, coalescing notifications. Section bytes remain in the authoritative world store. */
public final class LodUpdateQueue {
    public record Key(String dimension, long section) {}
    public record Ticket(Key key, long revision) {}
    public record Stats(int pending, int peak, long coalesced, long overflow, int repairs) {}
    private static final class Entry {
        long revision;
        Set<UUID> remaining;
        Entry(long revision) { this.revision = revision; }
    }

    private final int capacity;
    private final LinkedHashMap<Key, Entry> pending = new LinkedHashMap<>();
    private final Map<String, Long> repairs = new HashMap<>();
    private long revision, coalesced, overflow;
    private int peak;
    private boolean closed;

    public LodUpdateQueue(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    /** Never blocks an ingest worker or the server thread. Overflow schedules a store sweep. */
    public synchronized boolean dirty(Key key) {
        if (this.closed) return false;
        if (!this.put(key)) {
            this.repairs.put(key.dimension(), ++this.revision);
            this.overflow++;
            return false;
        }
        return true;
    }

    private boolean put(Key key) {
        Entry existing = this.pending.get(key);
        if (existing != null) {
            existing.revision = ++this.revision;
            existing.remaining = null;
            this.coalesced++;
            return true;
        }
        if (this.pending.size() == this.capacity) return false;
        this.pending.put(key, new Entry(++this.revision));
        this.peak = Math.max(this.peak, this.pending.size());
        return true;
    }

    /** Only the background recovery iterator may wait; it retains no section/payload references. */
    public synchronized void recover(Key key) throws InterruptedException {
        // Leave half the mailbox for live changes. Otherwise a sweep fills every freed
        // slot, ordinary world ticks overflow again, and a quiet trickle forces endless
        // full-store sweeps even when consumers can easily keep up with those changes.
        int recoveryCapacity = Math.max(1, this.capacity / 2);
        while (!this.closed) {
            if ((this.pending.containsKey(key) || this.pending.size() < recoveryCapacity) && this.put(key)) return;
            this.wait();
        }
        if (this.closed) throw new InterruptedException("LOD stream closed");
    }

    public synchronized Ticket next(UUID player, Predicate<Key> interested, Function<Key, Set<UUID>> audience) {
        Iterator<Map.Entry<Key, Entry>> iterator = this.pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var next = iterator.next();
            Entry entry = next.getValue();
            if (entry.remaining == null) entry.remaining = new HashSet<>(audience.apply(next.getKey()));
            if (!interested.test(next.getKey())) entry.remaining.remove(player);
            if (entry.remaining.isEmpty()) {
                iterator.remove();
                this.notifyAll();
            } else if (entry.remaining.contains(player)) {
                return new Ticket(next.getKey(), entry.revision);
            }
        }
        return null;
    }

    public synchronized void complete(UUID player, Ticket ticket) {
        Entry entry = this.pending.get(ticket.key());
        // A dirty callback racing serialization/send must survive the old acknowledgement.
        if (entry == null || entry.revision != ticket.revision()) return;
        entry.remaining.remove(player);
        if (entry.remaining.isEmpty()) {
            this.pending.remove(ticket.key());
            this.notifyAll();
        }
    }

    public synchronized void forget(UUID player) {
        this.pending.values().removeIf(entry -> entry.remaining != null
                && entry.remaining.remove(player) && entry.remaining.isEmpty());
        this.notifyAll();
    }

    public synchronized Map<String, Long> repairs() { return Map.copyOf(this.repairs); }

    public synchronized void repaired(String dimension, long startedRevision) {
        this.repairs.remove(dimension, startedRevision);
    }

    public synchronized Stats stats() {
        return new Stats(this.pending.size(), this.peak, this.coalesced, this.overflow, this.repairs.size());
    }

    public synchronized void close() {
        this.closed = true;
        this.clear();
    }

    public synchronized void clear() {
        this.pending.clear();
        this.repairs.clear();
        this.notifyAll();
    }
}
