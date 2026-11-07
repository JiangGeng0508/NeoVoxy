package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NodeBatchQueueTest {
    @Test void reservesBeforeReadbackAndOwnsActualCopyUntilProcessingCompletes() {
        long before = MemoryBuffer.getTotalSize();
        var tickets = new AtomicInteger();
        try (var queue = new NodeBatchQueue(64, 2, tickets::incrementAndGet)) {
            var first = queue.reserve(48);
            assertNotNull(first);
            assertNull(queue.reserve(17));
            assertEquals(0, queue.stats().nativeBytes());
            var source = new MemoryBuffer(32).zero();
            try {
                MemoryUtil.memPutInt(source.address, 9);
                MemoryUtil.memPutLong(source.address + 8, 123);
                assertTrue(first.publish(source.address, 16, 1));
                assertEquals(9, MemoryUtil.memGetInt(source.address), "mapped GPU input stays untouched");
                assertEquals(16, queue.stats().reservedBytes());
                var second = queue.reserve(48);
                assertNotNull(second);
                assertNull(queue.reserve(1));
                assertSame(first, queue.poll());
                assertEquals(0, queue.stats().queued());
                assertEquals(64, queue.stats().reservedBytes(), "poll must retain the running reservation");
                assertEquals(123, MemoryUtil.memGetLong(first.buffer().address + 8));
                assertEquals(1, MemoryUtil.memGetInt(first.buffer().address));
                first.close(); first.close();
                second.close();
                assertEquals(1, tickets.get());
                assertEquals(0, queue.stats().entries());
            } finally { source.free(); }
        }
        assertEquals(before, MemoryBuffer.getTotalSize());
    }

    @Test void byteAndEntryLimitsIndependentlyRecoverAndEmptyReadbacksRelease() {
        try (var bytes = new NodeBatchQueue(8, 5, () -> {});
             var entries = new NodeBatchQueue(1024, 1, () -> {})) {
            assertNull(bytes.reserve(9));
            var a = bytes.reserve(8);
            assertNull(bytes.reserve(1));
            a.close();
            bytes.reserve(8).close();
            var b = entries.reserve(1);
            assertNull(entries.reserve(1));
            b.close();
            entries.reserve(1024).close();
            assertEquals(0, entries.stats().reservedBytes());
            assertEquals(0, bytes.stats().reservedBytes());
        }
    }

    @Test void closeCancelsQueuedAndUnreturnedReadbacksAndRejectsLateCallbacks() {
        long before = MemoryBuffer.getTotalSize();
        var queue = new NodeBatchQueue(64, 4, () -> {});
        var source = new MemoryBuffer(16).zero();
        try {
            var downloading = queue.reserve(16);
            var queued = queue.reserve(16);
            queued.publish(source.address, 16, null);
            queue.close(); queue.close();
            assertFalse(downloading.publish(source.address, 16, null));
            assertNull(queue.reserve(8));
            assertNull(queue.poll());
            assertEquals(0, queue.stats().reservedBytes());
            assertEquals(0, queue.stats().nativeBytes());
        } finally { queue.close(); source.free(); }
        assertEquals(before, MemoryBuffer.getTotalSize());
    }

    @Test void invalidInputAndFailedWakeupCannotLeakOrPublishPartialBatch() {
        long before = MemoryBuffer.getTotalSize();
        var source = new MemoryBuffer(16).zero();
        try (var queue = new NodeBatchQueue(64, 4, () -> { throw new IllegalStateException("injected shutdown"); })) {
            var batch = queue.reserve(16);
            assertThrows(IllegalArgumentException.class, () -> batch.publish(source.address, 17, null));
            assertThrows(IllegalArgumentException.class, () -> batch.publish(source.address, 16, 2));
            assertEquals(0, queue.stats().nativeBytes());
            assertThrows(IllegalStateException.class, () -> batch.publish(source.address, 16, 1));
            assertNull(queue.poll());
            assertEquals(0, queue.stats().entries());
        } finally { source.free(); }
        assertEquals(before, MemoryBuffer.getTotalSize());
    }

    @Test void concurrentReservationsNeverOversubscribeAndReturnAllCapacity() throws Exception {
        try (var queue = new NodeBatchQueue(128, 8, () -> {});
             var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                start.await();
                for (int j = 0; j < 1000; j++) {
                    var batch = queue.reserve(32);
                    var stats = queue.stats();
                    assertTrue(stats.reservedBytes() >= 0 && stats.reservedBytes() <= 128);
                    assertTrue(stats.entries() <= 4);
                    if (batch != null) batch.close();
                }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(0, queue.stats().entries());
            assertEquals(0, queue.stats().reservedBytes());
        }
    }
}
