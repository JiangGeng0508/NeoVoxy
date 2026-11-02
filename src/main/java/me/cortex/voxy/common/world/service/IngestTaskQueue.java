package me.cortex.voxy.common.world.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BinaryOperator;

/** One running and at most one latest pending snapshot per world/section. */
final class IngestTaskQueue<T> {
    static final long HEAP_TARGET = 64L << 20;
    static final int TASK_TARGET = 2000;

    record Key(Object world, int x, int y, int z) {
        @Override public boolean equals(Object other) {
            return other instanceof Key key && world == key.world && x == key.x && y == key.y && z == key.z;
        }
        @Override public int hashCode() {
            return ((System.identityHashCode(world) * 31 + x) * 31 + y) * 31 + z;
        }
    }
    record Task<T>(Key key, T value, long arrayBytes, long compatibilityBytes) {
        Task {
            if (arrayBytes < 0 || compatibilityBytes < 0) throw new IllegalArgumentException("Negative payload size");
        }
    }
    record Offered<T>(boolean accepted, Task<T> replaced) {}
    private static final class Slot<T> {
        Task<T> pending, running;
    }
    record Stats(int queued, int running, int keys, long arrayBytes, long compatibilityBytes,
                 long peakBytes, long coalesced, long refused, long overTarget, long byteTarget, int taskTarget) {
        int tasks() { return queued + running; }
        double pressure() {
            return Math.max((double) (arrayBytes + compatibilityBytes) / byteTarget, (double) tasks() / taskTarget);
        }
    }

    private final HashMap<Key, Slot<T>> slots = new HashMap<>();
    private final ArrayDeque<Slot<T>> ready = new ArrayDeque<>();
    private final Runnable submit;
    private final BinaryOperator<Task<T>> merge;
    private final long byteTarget;
    private final int taskTarget;
    private int queued, running;
    private long arrayBytes, compatibilityBytes, peakBytes, coalesced, refused, overTarget;
    private boolean closed;

    IngestTaskQueue(Runnable submit) { this(submit, HEAP_TARGET, TASK_TARGET); }
    IngestTaskQueue(Runnable submit, long byteTarget, int taskTarget) {
        this(submit, byteTarget, taskTarget, (previous, next) -> next);
    }
    IngestTaskQueue(Runnable submit, long byteTarget, int taskTarget, BinaryOperator<Task<T>> merge) {
        if (byteTarget <= 0 || taskTarget <= 0) throw new IllegalArgumentException("Invalid ingest target");
        this.submit = submit;
        this.merge = merge;
        this.byteTarget = byteTarget;
        this.taskTarget = taskTarget;
    }

    synchronized boolean isOpen() { return !closed; }

    synchronized Offered<T> offer(Task<T> task, boolean replayable) {
        if (closed) return new Offered<>(false, null);
        var slot = slots.get(task.key());
        var previous = slot == null ? null : slot.pending;
        if (previous != null) task = merge.apply(previous, task);
        long nextBytes = arrayBytes + compatibilityBytes + task.arrayBytes() + task.compatibilityBytes()
                - (previous == null ? 0 : previous.arrayBytes() + previous.compatibilityBytes());
        int nextTasks = queued + running + (previous == null ? 1 : 0);
        boolean full = nextBytes > byteTarget || nextTasks > taskTarget;
        // Ordinary event producers cannot replay unloaded chunks. Never discard their newest input
        // just to meet a target; only the explicit completion/retry API may reject for pressure.
        if (full && replayable) { refused++; return new Offered<>(false, null); }
        if (slot == null) { slot = new Slot<>(); slots.put(task.key(), slot); }
        if (previous != null) { account(previous, -1); coalesced++; }
        else queued++;
        slot.pending = task;
        account(task, 1);
        peakBytes = Math.max(peakBytes, arrayBytes + compatibilityBytes);
        if (full) overTarget++;
        if (previous == null && slot.running == null) {
            ready.addLast(slot);
            try { submit.run(); }
            catch (RuntimeException | Error failure) {
                ready.removeLastOccurrence(slot);
                slots.remove(task.key());
                queued--;
                account(task, -1);
                throw failure;
            }
        }
        return new Offered<>(true, previous);
    }

    synchronized Task<T> take() {
        var slot = ready.pollFirst();
        if (slot == null) return null;
        var task = slot.pending;
        slot.pending = null;
        slot.running = task;
        queued--; running++;
        return task;
    }

    synchronized void complete(Task<T> task) {
        var slot = slots.get(task.key());
        if (slot == null || slot.running != task) throw new IllegalStateException("Ingest task not running");
        slot.running = null;
        running--;
        account(task, -1);
        if (slot.pending == null) slots.remove(task.key());
        else { ready.addLast(slot); submit.run(); }
        notifyAll();
    }

    /** Pending tasks are returned to their owner for cancellation; running tasks still own bytes. */
    synchronized List<Task<T>> close() {
        closed = true;
        var cancelled = new ArrayList<Task<T>>();
        var iterator = slots.values().iterator();
        while (iterator.hasNext()) {
            var slot = iterator.next();
            if (slot.pending != null) {
                cancelled.add(slot.pending);
                account(slot.pending, -1);
                slot.pending = null;
            }
            if (slot.running == null) iterator.remove();
        }
        queued = 0;
        ready.clear();
        notifyAll();
        return cancelled;
    }

    synchronized Stats stats() {
        return new Stats(queued, running, slots.size(), arrayBytes, compatibilityBytes,
                peakBytes, coalesced, refused, overTarget, byteTarget, taskTarget);
    }

    private void account(Task<T> task, int sign) {
        arrayBytes += sign * task.arrayBytes();
        compatibilityBytes += sign * task.compatibilityBytes();
    }
}
