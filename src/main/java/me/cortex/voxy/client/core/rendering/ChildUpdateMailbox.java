package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import me.cortex.voxy.common.world.WorldSection;

/** One scalar state per child watch; updates neither allocate entries nor retain sections. */
final class ChildUpdateMailbox extends Long2ShortOpenHashMap {
    private static final int DIRTY = 256;
    private int pending, cursor;
    private long coalesced;
    private boolean closed;
    private final Runnable wakeup;
    private final long[] positions = new long[256];
    private final byte[] masks = new byte[256];

    ChildUpdateMailbox(Runnable wakeup) { this.wakeup = wakeup; }

    synchronized void watch(long position) {
        if (!closed && !containsKey(position)) put(position, (short) 0);
    }

    synchronized void unwatch(long position) {
        if ((remove(position) & DIRTY) != 0) pending--;
        // Removal can shift dirty entries behind the scan cursor. Wraparound will visit them.
    }

    void offer(WorldSection section) {
        boolean wake;
        synchronized (this) {
            if (closed || !containsKey(section.key)) return;
            wake = (get(section.key) & DIRTY) == 0;
            if (wake) pending++;
            else coalesced++;
            // Read under the mailbox lock so a delayed old callback reads the current mask too.
            put(section.key, (short) (DIRTY | Byte.toUnsignedInt(section.getNonEmptyChildren())));
        }
        if (wake) wakeup.run();
    }

    /** Single consumer; bounded scan and reusable output, callbacks outside the lock. */
    int drain(SectionUpdateRouter.ChildConsumer consumer, int scanLimit) {
        int count = 0;
        synchronized (this) {
            for (int scanned = 0; pending != 0 && scanned < key.length && scanLimit > 0 && count < positions.length; scanned++, scanLimit--) {
                int slot = cursor++;
                if (cursor == key.length) cursor = 0;
                if ((slot == n ? containsNullKey : key[slot] != 0) && (value[slot] & DIRTY) != 0) {
                    positions[count] = key[slot];
                    masks[count++] = (byte) value[slot];
                    value[slot] = 0;
                    pending--;
                }
            }
        }
        for (int i = 0; i < count; i++) consumer.accept(positions[i], masks[i]);
        return count;
    }

    @Override protected void rehash(int size) { super.rehash(size); cursor = 0; }

    synchronized boolean hasPending() { return pending != 0; }

    synchronized SectionUpdateRouter.ChildStats stats() {
        return new SectionUpdateRouter.ChildStats(size(), pending, coalesced,
                key.length * 8L + value.length * 2L, positions.length * 8L + masks.length);
    }

    synchronized void close() {
        closed = true;
        clear();
        trim();
        pending = 0;
    }
}
