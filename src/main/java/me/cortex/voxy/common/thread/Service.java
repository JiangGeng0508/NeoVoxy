package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.Pair;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;

import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class Service {
    private final PerThreadContextExecutor executor;
    private final ServiceManager sm;
    final long weight;
    final String name;
    final BooleanSupplier limiter;
    private final String queueTimingName;
    private final String workTimingName;

    private final Semaphore tasks = new Semaphore(0);
    private final LongArrayFIFOQueue enqueuedAt = new LongArrayFIFOQueue();
    private int running;
    private volatile boolean isLive = true;
    private volatile boolean isStopping = false;

    Service(Supplier<Pair<Runnable, Runnable>> ctxSupplier, ServiceManager sm, long weight, String name, BooleanSupplier limiter) {
        this.sm = sm;
        this.weight = weight;
        this.name = name;
        this.limiter = limiter;
        this.queueTimingName = "queue/" + name;
        this.workTimingName = "work/" + name;

        this.executor = new PerThreadContextExecutor(ctxSupplier, e->sm.handleException(this, e));
    }

    public synchronized void execute() {
        if (this.isStopping) {
            Logger.error("Tried executing on a dead service");
            return;
        }
        this.enqueuedAt.enqueue(System.nanoTime());
        this.sm.execute(this);
        this.tasks.release();
        this.sm.wake();
    }

    boolean runJob() {
        long queued;
        synchronized (this) {
            if (this.isStopping || !this.isLive || !this.tasks.tryAcquire()) return false;
            queued = this.enqueuedAt.dequeueLong();
            this.running++;
        }
        long start = System.nanoTime();
        TaskTiming.record(this.queueTimingName, queued, start);
        try {
            if (!this.executor.run()) throw new IllegalStateException("Executor failed to run");
            return true;
        } finally {
            TaskTiming.record(this.workTimingName, start, System.nanoTime());
            synchronized (this) {
                this.running--;
                this.notifyAll();
            }
        }
    }

    public boolean isLive() {
        return this.isLive&&!this.isStopping;
    }

    public int numJobs() {
        return this.tasks.availablePermits();
    }

    public void blockTillEmpty() {
        while (this.isLive() && this.numJobs() != 0) {
            Thread.yield();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public int shutdown() {
        synchronized (this) {
            if (this.isStopping) throw new IllegalStateException("Service not live");
            this.isStopping = true;
        }
        this.sm.removeService(this);//Remove the service this is so that new jobs are never executed
        synchronized (this) {
            while (this.running != 0) {
                try { this.wait(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }
        }
        this.executor.shutdown();//Await shutdown of all running jobs
        int remaining = this.drain();
        this.isLive = false;//Mark the service as dead
        return remaining;
    }

    public synchronized boolean steal() {
        if (!this.tasks.tryAcquire()) {
            return false;
        }
        this.sm.remJobs(1);
        TaskTiming.record(this.queueTimingName, this.enqueuedAt.dequeueLong(), System.nanoTime());
        return true;
    }

    public synchronized int drain() {
        int tasks = this.tasks.drainPermits();
        this.enqueuedAt.clear();
        if (tasks != 0) {
            this.sm.remJobs(tasks);
        }
        return tasks;
    }
}
