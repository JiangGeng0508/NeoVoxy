package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;

    private final Service service;
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService(ServiceManager sm) {
        this(sm, System::nanoTime);
    }

    //Batch bounds: sections are uncompressed ~66KB each on the wire, so cap by count AND bytes or a
    //world import piles hundreds of MB of native memory into one write.
    private static final int MAX_BATCH_SECTIONS = 64;
    private static final long MAX_BATCH_BYTES = 4L << 20;
    private static final long MAX_BATCH_NANOS = 4_000_000;
    private final java.util.function.LongSupplier clock;

    SectionSavingService(ServiceManager sm, java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.service = sm.createServiceNoCleanup(() -> this::processJob, 2, "Section saving service");
    }

    private static final ThreadLocal<Boolean> DRAINING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private void processJob() {
        this.processJob(true);
    }

    //consumePermits=false is only for the shutdown drain: Service.shutdown() has already zeroed the
    //permits, so steal() would always fail and the big shutdown save would degrade to one at a time -
    //exactly the case batching is worth the most.
    private void processJob(boolean consumePermits) {
        SaveEntry task;
        synchronized (this.saveQueue) { task = this.saveQueue.poll(); }
        if (task == null) {
            return;
        }
        boolean outermost = !DRAINING.get();
        var staged = new ArrayList<WorldSection>(MAX_BATCH_SECTIONS);
        long start = this.clock.getAsLong();
        WorldEngine batchEngine = task.engine();
        SectionStorage.SectionSaveBatch batch = null;
        //Raised inside the try so nothing can throw between here and the finally that lowers it - a
        //flag stuck true would silently drop this thread's queue backpressure for the rest of its life.
        try {
            if (outermost) {
                DRAINING.set(Boolean.TRUE);
            }
            try {
                batch = batchEngine.storage.createSaveBatch();
            } catch (Exception failure) {
                task.section().exchangeIsInSaveQueue(false);
                task.section().markDirty();
                task.section().release();
                Logger.error("Voxy saver failed to create a save batch", failure);
                return;
            }
            int processed = 0;
            while (true) {
                this.stageSection(batch, staged, task.section());
                if (++processed >= MAX_BATCH_SECTIONS || batch.dataSize() >= MAX_BATCH_BYTES
                        || this.clock.getAsLong() - start >= MAX_BATCH_NANOS) break;
                // Coordinate the peek and poll across saver workers. A different engine must get
                // its own invocation, and every additional queue entry consumes one permit.
                synchronized (this.saveQueue) {
                    var next = this.saveQueue.peek();
                    if (next == null || next.engine() != batchEngine) break;
                    if (consumePermits && !this.service.steal()) break;
                    task = this.saveQueue.poll();
                }
            }
        } finally {
            try {
                if (batch != null) {
                    try { this.finishBatch(batch, staged); }
                    finally { batch.close(); }
                }
            } finally {
                if (outermost) {
                    DRAINING.set(Boolean.FALSE);
                }
            }
        }
    }

    private void stageSection(SectionStorage.SectionSaveBatch batch, List<WorldSection> staged, WorldSection section) {
        section.assertNotFree();
        try {
            //Clear the dirty flag only after winning the queue exchange - clearing it first leaves a
            //window where a concurrent re-dirty is silently swallowed
            if (section.exchangeIsInSaveQueue(false)) {
                section.setNotDirty();
                batch.add(section);
                staged.add(section);
                return;
            } else {
                section.setNotDirty();
            }
        } catch (Exception e) {
            Logger.error("Voxy saver had an exception while executing please check logs and report error", e);
            section.markDirty();
        }
        //Anything that did not make it into the batch is released now, as before
        section.release();
    }

    private void finishBatch(SectionStorage.SectionSaveBatch batch, List<WorldSection> staged) {
        if (staged.isEmpty()) {
            return;
        }
        boolean committed = true;
        try {
            batch.commit();
            me.cortex.voxy.commonImpl.PerfStats.saveBatchCommits.increment();
            me.cortex.voxy.commonImpl.PerfStats.saveBatchSections.add(staged.size());
        } catch (Exception e) {
            committed = false;
            Logger.error("Voxy saver failed to commit a batch of " + staged.size() + " sections", e);
        }
        for (var section : staged) {
            if (!committed) {
                //Dirty was already cleared, so re-mark it: tryUnload's save branch will requeue it
                section.markDirty();
            }
            section.release();
        }
        staged.clear();
    }


    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
            }

            //Hard limit the save count to prevent OOM. Skipped while this thread is already draining -
            //see DRAINING.
            if ((!nonBlocking) && !DRAINING.get() && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                //If we are still full, process entries in the queue ourselves instead of waiting for the service
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            synchronized (this.saveQueue) {
                this.saveQueue.add(new SaveEntry(in, section));
                this.service.execute();
            }
            return true;
        }
        return false;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining.");
        }
        this.service.shutdown();
        //Manually save any remaining entries. Permits are already zeroed by shutdown(), so drain
        //without stealing them - otherwise this degrades to one section per batch.
        while (!this.saveQueue.isEmpty()) {
            this.processJob(false);
        }
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
