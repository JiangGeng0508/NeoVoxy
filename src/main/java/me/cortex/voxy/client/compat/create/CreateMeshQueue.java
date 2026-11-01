package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.thread.TaskTiming;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Client-thread capture/publication, one worker for CPU-only simplification. */
final class CreateMeshQueue implements AutoCloseable {
    static final long MAX_BYTES = 32L << 20, FRAME_NANOS = 2_000_000, FRAME_BYTES = 2L << 20;
    static final int MAX_TASKS = 1024;
    interface Upload extends AutoCloseable {
        long step(long bytes);
        boolean done();
        DistantMesh finish();
        void close();
    }
    record Stats(int tasks, int oversized, long nativeBytes, long heapBytes, long reservedBytes, long peakReserved,
                 long captured, long prepared, long published, long stale, long refused, long failed,
                 long lastFrameNanos, long lastFrameBytes, long peakFrameNanos, long peakFrameBytes) {}
    static final class Capacity extends RuntimeException {
        Capacity() { super("Create CPU mesh budget exhausted", null, false, false); }
    }
    private enum State { CAPTURE, PREPARE, RUNNING, READY, UPLOAD }
    private final class Job implements DistantMeshBuilder.Allocation {
        final Object key;
        final Supplier<DistantMeshBuilder.CpuMesh> capture;
        final Consumer<DistantMesh> publish;
        final Runnable abandoned;
        State state = State.CAPTURE;
        DistantMeshBuilder.CpuMesh cpu;
        Upload upload;
        Throwable failure;
        boolean cancelled, refusedCapture;
        long heap, heldNative, requiredReservation, queuedAt = clock.getAsLong();
        Job(Object key, Supplier<DistantMeshBuilder.CpuMesh> capture, Consumer<DistantMesh> publish, Runnable abandoned) {
            this.key = key; this.capture = capture; this.publish = publish; this.abandoned = abandoned;
        }
        public void resize(int previous, int next) {
            synchronized (CreateMeshQueue.this) {
                long delta = (long) next - previous;
                // Coarse vertex levels geometrically decrease, totalling less than the raw vertices;
                // planar index levels also fit in one raw buffer. Reserve before allocating either.
                if (delta > 0 && delta * 2 > byteLimit - reservedBytes) {
                    this.requiredReservation = (this.heldNative + delta) * 2;
                    this.refusedCapture = true; refused++; throw new Capacity();
                }
                this.heldNative += delta;
                nativeBytes += delta; reservedBytes += delta * 2;
                peakReserved = Math.max(peakReserved, reservedBytes);
            }
        }
        public void release(int capacity) {
            synchronized (CreateMeshQueue.this) {
                nativeBytes -= capacity; reservedBytes -= capacity * 2;
                this.heldNative -= capacity;
                heapBytes -= this.heap; this.heap = 0;
            }
        }
        void freeCpu() { if (this.cpu != null) { this.cpu.free(); this.cpu = null; } }
    }

    private final LinkedHashMap<Object, Job> jobs = new LinkedHashMap<>();
    private final Service worker;
    private final LongSupplier clock;
    private final Function<DistantMeshBuilder.CpuMesh, Upload> uploader;
    private final Consumer<DistantMeshBuilder.CpuMesh> preparer;
    private final long byteLimit;
    private boolean scheduled, closed, preferUpload = true;
    private long nativeBytes, heapBytes, reservedBytes, peakReserved;
    private long captured, prepared, published, stale, refused, failed, frameNanos, frameBytes;
    private long peakFrameNanos, peakFrameBytes;

    CreateMeshQueue(ServiceManager manager) { this(manager, System::nanoTime, DistantMeshBuilder.CpuMesh::beginUpload, MAX_BYTES); }
    CreateMeshQueue(ServiceManager manager, LongSupplier clock, Function<DistantMeshBuilder.CpuMesh, Upload> uploader, long byteLimit) {
        this(manager, clock, uploader, byteLimit, DistantMeshBuilder.CpuMesh::prepare);
    }
    CreateMeshQueue(ServiceManager manager, LongSupplier clock, Function<DistantMeshBuilder.CpuMesh, Upload> uploader,
                    long byteLimit, Consumer<DistantMeshBuilder.CpuMesh> preparer) {
        this.clock = clock; this.uploader = uploader; this.byteLimit = byteLimit;
        this.preparer = preparer;
        this.worker = manager.createServiceNoCleanup(() -> this::prepareOne, 1, "Create mesh simplification");
    }

    /** Repeated keys replace only unpublished work; installed meshes belong to their callers. */
    boolean request(Object key, Supplier<DistantMeshBuilder.CpuMesh> capture, Consumer<DistantMesh> publish, Runnable abandoned) {
        this.cancel(key);
        synchronized (this) {
            if (this.closed || this.jobs.size() >= MAX_TASKS) { this.refused++; return false; }
            this.jobs.put(key, new Job(key, capture, publish, abandoned));
            return true;
        }
    }

    void cancel(Object key) {
        Job previous;
        synchronized (this) {
            previous = this.jobs.remove(key);
            if (previous == null) return;
            previous.cancelled = true; this.stale++;
            if (previous.state != State.RUNNING) previous.freeCpu();
        }
        this.closeUpload(previous);
        this.abandon(previous);
    }

    void frame() {
        this.frameNanos = this.frameBytes = 0;
        var attempted = new HashSet<Job>();
        while (this.frameNanos < FRAME_NANOS) {
            Job job;
            synchronized (this) {
                if (this.closed) return;
                Job capture = null, upload = null;
                for (Job candidate : this.jobs.values()) {
                    if (candidate.state == State.CAPTURE && !attempted.contains(candidate) && capture == null
                            && candidate.requiredReservation <= this.byteLimit - this.reservedBytes) capture = candidate;
                    if ((candidate.state == State.READY || candidate.state == State.UPLOAD)
                            && this.frameBytes < FRAME_BYTES && upload == null) upload = candidate;
                }
                job = upload != null && (this.preferUpload || capture == null) ? upload : capture;
                if (job == null) return;
                this.preferUpload = !this.preferUpload;
            }
            long start = this.clock.getAsLong();
            if (job.state == State.CAPTURE) { attempted.add(job); this.capture(job); }
            else this.upload(job);
            this.frameNanos += Math.max(0, this.clock.getAsLong() - start);
            this.peakFrameNanos = Math.max(this.peakFrameNanos, this.frameNanos);
            this.peakFrameBytes = Math.max(this.peakFrameBytes, this.frameBytes);
        }
    }

    private void capture(Job job) {
        job.refusedCapture = false;
        DistantMeshBuilder.ALLOCATION.set(job);
        try {
            job.cpu = job.capture.get();
            if (job.refusedCapture) throw new Capacity(); // A third-party callback may swallow the allocation exception.
            synchronized (this) {
                this.captured++;
                if (job.cancelled || this.closed) { job.freeCpu(); return; }
                job.state = job.cpu == null ? State.READY : State.PREPARE;
                this.schedule();
            }
        } catch (Capacity capacity) {
            job.freeCpu(); // Keep the recipe to retry after other jobs release their reservations.
            if (job.requiredReservation > this.byteLimit) {
                Logger.warn("Create mesh exceeds CPU queue budget; retaining the current model until its source changes (requires at least "
                        + job.requiredReservation + " bytes, budget " + this.byteLimit + ")");
            }
        } catch (Throwable failure) {
            job.freeCpu(); job.failure = failure; job.state = State.READY;
        } finally { DistantMeshBuilder.ALLOCATION.remove(); }
    }

    private void schedule() {
        if (this.closed || this.scheduled || this.jobs.values().stream().noneMatch(j -> j.state == State.PREPARE)) return;
        this.scheduled = true; this.worker.execute();
    }

    private void prepareOne() {
        Job job;
        synchronized (this) {
            job = this.jobs.values().stream().filter(j -> j.state == State.PREPARE).findFirst().orElse(null);
            if (job == null || this.closed) { this.scheduled = false; return; }
            job.state = State.RUNNING;
        }
        long start = this.clock.getAsLong();
        TaskTiming.record("queue/Create mesh", job.queuedAt, start);
        try { this.preparer.accept(job.cpu); }
        catch (Throwable failure) { job.failure = failure; }
        finally {
            TaskTiming.record("work/Create simplify", start, this.clock.getAsLong());
            synchronized (this) {
                this.prepared++;
                job.heap = job.cpu.heapBytes(); this.heapBytes += job.heap;
                if (job.cancelled || this.closed) job.freeCpu(); else job.state = State.READY;
                this.scheduled = false; this.schedule();
            }
        }
    }

    private void upload(Job job) {
        DistantMesh completed = null;
        try {
            if (job.failure != null) throw new IllegalStateException("Create mesh preparation failed", job.failure);
            if (job.cpu != null) {
                if (job.upload == null) { job.upload = this.uploader.apply(job.cpu); job.state = State.UPLOAD; }
                this.frameBytes += job.upload.step(FRAME_BYTES - this.frameBytes);
                if (!job.upload.done()) return;
                completed = job.upload.finish();
            }
            synchronized (this) { this.jobs.remove(job.key, job); }
            job.freeCpu();
            job.publish.accept(completed); completed = null;
            this.published++;
        } catch (Throwable failure) {
            this.failed++;
            synchronized (this) { this.jobs.remove(job.key, job); }
            if (completed != null) completed.free();
            this.closeUpload(job);
            job.freeCpu(); this.abandon(job);
            Logger.error("Create mesh build failed", failure);
        }
    }

    private void closeUpload(Job job) {
        if (job.upload == null) return;
        try { job.upload.close(); }
        catch (Throwable failure) { Logger.error("Create mesh upload cleanup failed", failure); }
        finally { job.upload = null; }
    }

    private void abandon(Job job) {
        try { job.abandoned.run(); }
        catch (Throwable failure) { Logger.error("Create mesh cancellation callback failed", failure); }
    }

    synchronized Stats stats() {
        int oversized = (int) jobs.values().stream().filter(j -> j.requiredReservation > byteLimit).count();
        return new Stats(jobs.size(), oversized, nativeBytes, heapBytes, reservedBytes, peakReserved,
                captured, prepared, published, stale, refused, failed, frameNanos, frameBytes, peakFrameNanos, peakFrameBytes);
    }

    @Override public void close() {
        synchronized (this) { if (this.closed) return; this.closed = true; }
        for (Object key : this.jobs.keySet().toArray()) this.cancel(key);
        this.worker.shutdown();
    }
}
