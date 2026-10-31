package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.thread.TaskTiming;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService.BuildTask;

import java.util.HashSet;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.Set;
import java.util.function.LongPredicate;

/** One task per position, including running and dependency-blocked tasks. Holds no section refs. */
final class MeshTaskQueue {
    enum State { RUNNABLE, WAITING, RUNNING, DONE }
    private final Long2ObjectOpenHashMap<BuildTask> tasks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Set<BuildTask>> dependents = new Long2ObjectOpenHashMap<>();
    static PriorityBlockingQueue<BuildTask> createRunnableQueue() { return new PriorityBlockingQueue<>(128, (a, b) -> {
        int priority = Integer.compare(a.priority, b.priority);
        return priority != 0 ? priority : Long.compare(a.order, b.order);
    }); }
    private final PriorityBlockingQueue<BuildTask> runnable;
    private final Runnable submit;
    private final LongPredicate ready;
    private long sequence;
    private int waiting;
    private boolean closed;

    MeshTaskQueue(Runnable submit, LongPredicate ready) {
        this(submit, ready, createRunnableQueue());
    }

    MeshTaskQueue(Runnable submit, LongPredicate ready, PriorityBlockingQueue<BuildTask> runnable) {
        this.submit = submit;
        this.ready = ready;
        this.runnable = runnable;
    }

    synchronized void enqueue(long position) {
        if (closed) return;
        var task = tasks.get(position);
        if (task != null) {
            if (task.state == State.RUNNING) task.changed = true;
            return;
        }
        task = new BuildTask(position);
        tasks.put(position, task);
        schedule(task);
    }

    private void schedule(BuildTask task) {
        task.state = State.RUNNABLE;
        task.updatePriority();
        task.order = sequence++;
        runnable.add(task);
        submit.run();
    }

    synchronized BuildTask take() {
        if (closed) return null;
        return claim(runnable.poll());
    }

    synchronized BuildTask claim(BuildTask task) {
        if (closed) return null;
        if (task != null) {
            task.state = State.RUNNING;
            task.changed = false;
        }
        return task;
    }

    synchronized void complete(BuildTask task) {
        if (closed) return;
        if (task.state != State.RUNNING) throw new IllegalStateException("Task is not running");
        if (task.changed) schedule(task);
        else {
            task.state = State.DONE;
            tasks.remove(task.position);
        }
    }

    synchronized void await(BuildTask task, LongOpenHashSet dependencies) {
        if (closed) return;
        if (task.state != State.RUNNING) throw new IllegalStateException("Task is not running");
        task.state = State.WAITING;
        task.waitStart = System.nanoTime();
        waiting++;
        for (long dependency : dependencies) {
            task.dependencies.add(dependency);
            dependents.computeIfAbsent(dependency, k -> new HashSet<>()).add(task);
        }
        // Register first, then recheck: a completion before registration must not lose a wakeup.
        for (long dependency : dependencies) if (ready.test(dependency)) dependencyReady(dependency);
        if (task.dependencies.isEmpty() && task.state == State.WAITING) wake(task);
    }

    synchronized void dependencyReady(long dependency) {
        if (closed) return;
        var affected = dependents.remove(dependency);
        if (affected == null) return;
        for (var task : affected) {
            task.dependencies.remove(dependency);
            if (task.dependencies.isEmpty()) wake(task);
        }
    }

    private void wake(BuildTask task) {
        waiting--;
        TaskTiming.record("model wait/mesh", task.waitStart, System.nanoTime());
        schedule(task);
    }

    synchronized int runnableCount() { return runnable.size(); }
    synchronized int waitingCount() { return waiting; }
    synchronized int taskCount() { return tasks.size(); }

    synchronized void close() {
        closed = true;
        for (var task : tasks.values()) {
            task.state = State.DONE;
            task.dependencies.clear();
        }
        tasks.clear();
        dependents.clear();
        runnable.clear();
        waiting = 0;
    }
}
