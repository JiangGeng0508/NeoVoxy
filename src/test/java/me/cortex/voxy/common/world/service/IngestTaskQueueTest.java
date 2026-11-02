package me.cortex.voxy.common.world.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IngestTaskQueueTest {
    private static IngestTaskQueue.Task<String> task(Object world, int x, String value, long bytes) {
        return new IngestTaskQueue.Task<>(new IngestTaskQueue.Key(world, x, 0, 0), value, bytes, 0);
    }

    @Test void replacesPendingPayloadWithoutAddingServicePermitsOrLosingWorldIdentity() {
        var permits = new AtomicInteger();
        var queue = new IngestTaskQueue<String>(permits::incrementAndGet, 100, 10);
        Object a = new Object(), b = new Object();
        var old = task(a, 0, "old", 90); var latest = task(a, 0, "latest", 30);
        assertTrue(queue.offer(old, false).accepted());
        assertSame(old, queue.offer(latest, false).replaced());
        queue.offer(task(b, 0, "other world", 20), false);
        assertEquals(2, permits.get()); assertEquals(50, queue.stats().arrayBytes());
        assertEquals(2, queue.stats().keys()); assertEquals(1, queue.stats().coalesced());
        assertSame(latest, queue.take()); queue.complete(latest);
        var other = queue.take(); assertEquals("other world", other.value()); queue.complete(other);
        assertEquals(0, queue.stats().tasks()); assertEquals(0, queue.stats().arrayBytes());
    }

    @Test void runningVersionRetainsBytesAndOnlyLatestPendingBecomesRunnableAfterCompletion() {
        var permits = new AtomicInteger(); var owner = new Object();
        var queue = new IngestTaskQueue<String>(permits::incrementAndGet, 100, 10);
        var running = task(owner, 1, "running", 20);
        queue.offer(running, false); assertSame(running, queue.take());
        var superseded = task(owner, 1, "superseded", 40);
        queue.offer(superseded, false);
        var latest = task(owner, 1, "latest", 60);
        assertSame(superseded, queue.offer(latest, false).replaced());
        assertEquals(80, queue.stats().arrayBytes()); assertEquals(1, queue.stats().running());
        assertEquals(1, permits.get()); assertNull(queue.take());
        queue.complete(running);
        assertEquals(2, permits.get()); assertSame(latest, queue.take());
        queue.complete(latest); assertEquals(0, queue.stats().arrayBytes());
    }

    @Test void retryableCapacityChecksIncludeRunningBytesAndAccountForReplacement() {
        var queue = new IngestTaskQueue<String>(() -> {}, 100, 2); var world = new Object();
        var first = task(world, 0, "first", 60); queue.offer(first, true); queue.take();
        var next = task(world, 0, "next", 40); assertTrue(queue.offer(next, true).accepted());
        assertFalse(queue.offer(task(world, 1, "rejected", 1), true).accepted());
        assertFalse(queue.offer(task(world, 0, "too big", 41), true).accepted());
        assertEquals(100, queue.stats().arrayBytes());
        var smaller = task(world, 0, "smaller", 1);
        assertSame(next, queue.offer(smaller, true).replaced());
        assertFalse(queue.offer(task(world, 1, "entry overflow", 0), true).accepted());
        assertEquals(3, queue.stats().refused());
        queue.complete(first); assertSame(smaller, queue.take()); queue.complete(smaller);
        assertTrue(queue.offer(task(world, 1, "retry", 100), true).accepted());
    }

    @Test void ordinaryUpdatesAreRetainedAboveTargetAndCanDisplaceAnOlderPendingSnapshot() {
        var queue = new IngestTaskQueue<String>(() -> {}, 10, 1); var world = new Object();
        queue.offer(task(world, 0, "one", 10), false);
        var latest = task(world, 1, "large authoritative update", 100);
        assertTrue(queue.offer(latest, false).accepted());
        assertEquals(110, queue.stats().arrayBytes()); assertEquals(1, queue.stats().overTarget());
        assertFalse(queue.offer(task(world, 2, "retry later", 1), true).accepted());
        var first = queue.take(); queue.complete(first);
        assertSame(latest, queue.take()); queue.complete(latest);
        assertEquals(0, queue.stats().tasks());
    }

    @Test void closeReturnsPendingTasksOnceButRunningOwnershipSurvivesUntilCompletion() {
        var queue = new IngestTaskQueue<String>(() -> {}, 100, 10); var world = new Object();
        var running = task(world, 0, "running", 10); queue.offer(running, false); queue.take();
        var pending = task(world, 0, "pending", 20); queue.offer(pending, false);
        var other = task(world, 1, "other", 30); queue.offer(other, false);
        var cancelled = queue.close();
        assertEquals(2, cancelled.size()); assertTrue(cancelled.contains(pending)); assertTrue(cancelled.contains(other));
        assertTrue(queue.close().isEmpty()); assertNull(queue.take());
        assertEquals(10, queue.stats().arrayBytes()); assertEquals(1, queue.stats().tasks());
        assertFalse(queue.offer(task(world, 0, "late", 20), false).accepted());
        queue.complete(running); assertEquals(0, queue.stats().tasks()); assertEquals(0, queue.stats().arrayBytes());
    }

    @Test void failedInitialSubmissionDoesNotTransferOwnershipOrLeavePayloadBehind() {
        var queue = new IngestTaskQueue<String>(() -> { throw new IllegalStateException("scheduler failure"); }, 100, 10);
        assertThrows(IllegalStateException.class, () -> queue.offer(task(new Object(), 0, "failed", 50), false));
        assertEquals(0, queue.stats().tasks()); assertEquals(0, queue.stats().arrayBytes());
        assertNull(queue.take()); assertTrue(queue.close().isEmpty());
    }

    @Test void mergedCompatibilityBytesParticipateInAdmissionBeforeOldOwnershipChanges() {
        var queue = new IngestTaskQueue<String>(() -> {}, 100, 10,
                (old, next) -> new IngestTaskQueue.Task<>(next.key(), next.value(), next.arrayBytes(), old.compatibilityBytes()));
        var key = new IngestTaskQueue.Key(new Object(), 0, 0, 0);
        var old = new IngestTaskQueue.Task<>(key, "materials", 10, 80);
        queue.offer(old, false);
        assertFalse(queue.offer(new IngestTaskQueue.Task<>(key, "too large with inherited materials", 21, 0), true).accepted());
        assertEquals(90, queue.stats().arrayBytes() + queue.stats().compatibilityBytes());
        assertSame(old, queue.offer(new IngestTaskQueue.Task<>(key, "fits", 20, 0), true).replaced());
        var latest = queue.take(); assertEquals("fits", latest.value()); assertEquals(80, latest.compatibilityBytes());
        queue.complete(latest); assertEquals(0, queue.stats().compatibilityBytes());
    }
}
