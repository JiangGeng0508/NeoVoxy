package me.cortex.voxy.common.util;

import java.util.Queue;

/** Per-invocation bound on unload callbacks, including callbacks that enqueue themselves. */
public final class ChunkUnloadBudget {
    private int remaining = -1;

    public boolean take(Queue<?> queue) {
        // ConcurrentLinkedQueue.size() traverses the queue: sample once, not per callback.
        if (this.remaining < 0) this.remaining = queue.size();
        if (this.remaining == 0) return false;
        this.remaining--;
        return true;
    }
}
