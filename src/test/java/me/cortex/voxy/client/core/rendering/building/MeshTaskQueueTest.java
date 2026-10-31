package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.model.ModelFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MeshTaskQueueTest {
    @Test void missingModelsNeverRunAgainUntilAllDependenciesComplete() {
        var submissions = new AtomicInteger();
        var queue = new MeshTaskQueue(submissions::incrementAndGet, id -> false);
        queue.enqueue(0);
        var task = queue.take();
        queue.await(task, new LongOpenHashSet(new long[]{1, 2}));
        for (int i = 0; i < 100; i++) { assertNull(queue.take()); queue.enqueue(0); }
        assertEquals(1, submissions.get());
        assertEquals(1, queue.waitingCount());
        queue.dependencyReady(1);
        assertNull(queue.take());
        queue.dependencyReady(2);
        queue.dependencyReady(2);
        assertEquals(2, submissions.get());
        assertSame(task, queue.take());
        queue.complete(task);
        assertEquals(0, queue.taskCount());
    }

    @Test void completionBeforeRegistrationIsFoundByTheReadinessRecheck() {
        var ready = new LongOpenHashSet();
        var queue = new MeshTaskQueue(() -> {}, ready::contains);
        queue.enqueue(0);
        var task = queue.take();
        ready.add(7); queue.dependencyReady(7);
        queue.await(task, new LongOpenHashSet(new long[]{7}));
        assertEquals(0, queue.waitingCount());
        assertSame(task, queue.take());
    }

    @Test void completionDuringTheRegistrationRecheckDoesNotDoubleSchedule() {
        var submissions = new AtomicInteger();
        var holder = new MeshTaskQueue[1];
        holder[0] = new MeshTaskQueue(submissions::incrementAndGet, id -> {
            holder[0].dependencyReady(id);
            return true;
        });
        var queue = holder[0];
        queue.enqueue(0);
        queue.await(queue.take(), new LongOpenHashSet(new long[]{1, 2}));
        assertEquals(2, submissions.get());
        assertEquals(1, queue.runnableCount());
        assertEquals(0, queue.waitingCount());
    }

    @Test void sharedDependenciesWakeEverySectionAndFluidIdsUseSeparateNamespace() {
        var queue = new MeshTaskQueue(() -> {}, id -> false);
        long block = ModelFactory.blockDependency(3), fluid = ModelFactory.fluidDependency(3);
        assertNotEquals(block, fluid);
        queue.enqueue(0); queue.enqueue(1);
        queue.await(queue.take(), new LongOpenHashSet(new long[]{block, fluid}));
        queue.await(queue.take(), new LongOpenHashSet(new long[]{block}));
        queue.dependencyReady(block);
        assertEquals(1, queue.waitingCount());
        assertEquals(1, queue.runnableCount());
        queue.dependencyReady(fluid);
        assertEquals(0, queue.waitingCount());
        assertEquals(2, queue.runnableCount());
    }

    @Test void runningUpdatesCoalesceIntoExactlyOneLaterRun() {
        var queue = new MeshTaskQueue(() -> {}, id -> false);
        queue.enqueue(0);
        var task = queue.take();
        for (int i = 0; i < 100; i++) queue.enqueue(0);
        assertEquals(1, queue.taskCount());
        assertNull(queue.take());
        queue.complete(task);
        assertSame(task, queue.take());
        queue.complete(task);
        assertEquals(0, queue.taskCount());
    }

    @Test void shutdownDiscardsRunnableAndWaitingTasksAndIgnoresLateCompletions() {
        var count = new AtomicInteger();
        var queue = new MeshTaskQueue(count::incrementAndGet, id -> false);
        queue.enqueue(0); queue.enqueue(1); queue.enqueue(2);
        var running = queue.take();
        queue.await(queue.take(), new LongOpenHashSet(new long[]{9}));
        queue.close();
        queue.complete(running); queue.dependencyReady(9); queue.enqueue(3);
        assertNull(queue.take());
        assertEquals(0, queue.waitingCount());
        assertEquals(0, queue.taskCount());
        assertEquals(3, count.get());
    }
}
