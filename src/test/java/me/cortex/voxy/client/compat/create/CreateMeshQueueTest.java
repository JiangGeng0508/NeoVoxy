package me.cortex.voxy.client.compat.create;

import me.cortex.voxy.common.thread.ServiceManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static me.cortex.voxy.testing.Reflect.get;
import static org.junit.jupiter.api.Assertions.*;

class CreateMeshQueueTest {
    static DistantMeshBuilder.CpuMesh capture(int cells) {
        var builder = new DistantMeshBuilder(true);
        try { DistantMeshBuilderTest.append(builder, DistantMeshFixtures.tiledPlane(cells)); return builder.capture(); }
        finally { builder.discard(); }
    }
    static class Upload implements CreateMeshQueue.Upload {
        long remaining;
        boolean closed;
        Upload(long remaining) { this.remaining = remaining; }
        public long step(long bytes) { long n = Math.min(remaining, Math.min(bytes, 65536)); remaining -= n; return n; }
        public boolean done() { return remaining == 0; }
        public DistantMesh finish() { assertTrue(done()); return null; }
        public void close() { closed = true; }
    }

    @Test void callbacksStayOnClientWhileExclusiveSnapshotIsSimplifiedByWorker() throws Exception {
        var manager = new ServiceManager(n -> {}); var client = Thread.currentThread();
        var cpus = new ArrayList<DistantMeshBuilder.CpuMesh>(); var published = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor();
             var queue = new CreateMeshQueue(manager, () -> 0L, cpu -> {
                 assertSame(client, Thread.currentThread()); return new Upload(cpu.byteSize());
             }, CreateMeshQueue.MAX_BYTES, cpu -> {
                 assertNotSame(client, Thread.currentThread()); cpu.prepare();
             })) {
            queue.request("train", () -> {
                assertSame(client, Thread.currentThread()); var cpu = capture(16); cpus.add(cpu); return cpu;
            }, mesh -> { assertSame(client, Thread.currentThread()); published.incrementAndGet(); }, () -> fail("abandoned"));
            queue.frame();
            assertSame(DistantMeshLod.EMPTY, get(cpus.getFirst(), "lod"));
            assertEquals(0, published.get());
            executor.submit(manager::tryRunAJob).get(10, TimeUnit.SECONDS);
            DistantMeshLod lod = get(cpus.getFirst(), "lod"); assertTrue(lod.grids.length > 0);
            assertTrue(queue.stats().heapBytes() > 0); queue.frame();
            assertEquals(1, published.get()); assertEquals(0, cpus.getFirst().byteSize());
            assertEquals(0, queue.stats().nativeBytes()); assertEquals(0, queue.stats().heapBytes());
            assertEquals(0, queue.stats().reservedBytes());
        } finally { manager.shutdown(); }
    }

    @Test void latestRecipeWinsAndCancellingRunningVersionRetainsItsBufferUntilWorkerFinishes() throws Exception {
        var manager = new ServiceManager(n -> {}); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var cpu = new DistantMeshBuilder.CpuMesh[1]; var publications = new ArrayList<Integer>();
        try (var executor = Executors.newSingleThreadExecutor();
             var queue = new CreateMeshQueue(manager, () -> 0L, mesh -> new Upload(mesh.byteSize()), CreateMeshQueue.MAX_BYTES,
                     mesh -> { entered.countDown(); await(release); mesh.prepare(); })) {
            queue.request("shape", () -> { fail("replaced recipe ran"); return null; }, m -> fail(), () -> {});
            queue.request("shape", () -> cpu[0] = capture(16), m -> publications.add(1), () -> {});
            queue.frame(); var task = executor.submit(manager::tryRunAJob); assertTrue(entered.await(10, TimeUnit.SECONDS));
            queue.request("shape", () -> capture(8), m -> publications.add(2), () -> {});
            assertTrue(cpu[0].nativeBytes() > 0, "a running worker still owns its snapshot");
            release.countDown(); task.get(10, TimeUnit.SECONDS);
            assertEquals(0, cpu[0].nativeBytes());
            queue.frame(); executor.submit(manager::tryRunAJob).get(10, TimeUnit.SECONDS); queue.frame();
            assertEquals(java.util.List.of(2), publications);
        } finally { release.countDown(); manager.shutdown(); }
    }

    @Test void capacityRefusalRetainsRecipeAndNeverPublishesTruncatedCapture() {
        var manager = new ServiceManager(n -> {}); var published = new AtomicInteger();
        try (var queue = new CreateMeshQueue(manager, () -> 0L, cpu -> new Upload(cpu.byteSize()), 128 * 1024)) {
            queue.request("first", () -> capture(8), m -> published.incrementAndGet(), () -> {});
            queue.request("second", () -> capture(8), m -> published.incrementAndGet(), () -> {});
            queue.frame(); assertEquals(1, queue.stats().captured()); assertEquals(2, queue.stats().tasks());
            assertTrue(queue.stats().refused() > 0); assertEquals(128 * 1024, queue.stats().reservedBytes());
            long busyRefusals = queue.stats().refused();
            for (int i = 0; i < 100; i++) queue.frame();
            assertEquals(busyRefusals, queue.stats().refused(), "retry only when enough bytes become available");
            manager.tryRunAJob(); queue.frame(); manager.tryRunAJob(); queue.frame();
            assertEquals(2, published.get()); assertEquals(0, queue.stats().reservedBytes());
            queue.request("too-large", () -> {
                var builder = new DistantMeshBuilder(true);
                try {
                    try { DistantMeshBuilderTest.append(builder, DistantMeshFixtures.tiledPlane(32)); }
                    catch (CreateMeshQueue.Capacity ignored) {} // Broken model swallows exceptions.
                    return builder.capture();
                } finally { builder.discard(); }
            }, m -> fail("partial model published"), () -> {});
            queue.frame(); assertEquals(1, queue.stats().tasks());
            assertEquals(0, queue.stats().nativeBytes()); assertEquals(0, queue.stats().reservedBytes());
            assertEquals(1, queue.stats().oversized());
            long refusals = queue.stats().refused();
            for (int i = 0; i < 100; i++) queue.frame();
            assertEquals(refusals, queue.stats().refused(), "oversized sources must not recapture every frame");
            queue.request("too-large", () -> capture(8), m -> published.incrementAndGet(), () -> {});
            queue.frame(); manager.tryRunAJob(); queue.frame();
            assertEquals(3, published.get()); assertEquals(0, queue.stats().oversized());
        } finally { manager.shutdown(); }
    }

    @Test void frameBudgetIsSharedAndLargeUploadWaitsForSubsequentFrames() {
        var manager = new ServiceManager(n -> {}); var now = new AtomicLong(); var captures = new AtomicInteger();
        var published = new AtomicInteger();
        try (var queue = new CreateMeshQueue(manager, now::get, cpu -> new Upload(5L << 20), CreateMeshQueue.MAX_BYTES)) {
            for (int i = 0; i < 2; i++) queue.request(i, () -> {
                captures.incrementAndGet(); now.addAndGet(2_000_001); return capture(8);
            }, m -> published.incrementAndGet(), () -> {});
            queue.frame(); assertEquals(1, captures.get()); assertEquals(2_000_001, queue.stats().lastFrameNanos());
            manager.tryRunAJob(); queue.frame(); assertEquals(2, captures.get());
            while (manager.tryRunAJob() == 0) {}
            for (int i = 0; i < 8 && queue.stats().tasks() != 0; i++) {
                queue.frame(); assertTrue(queue.stats().lastFrameBytes() <= 2L << 20);
            }
            assertEquals(2, published.get()); assertEquals(0, queue.stats().reservedBytes());
        } finally { manager.shutdown(); }
    }

    @Test void cancellingPartialUploadClosesItWithoutPublishingAndCloseReleasesReadyJobs() {
        var manager = new ServiceManager(n -> {}); var uploads = new ArrayList<Upload>();
        var queue = new CreateMeshQueue(manager, () -> 0L, cpu -> { var u = new Upload(5L << 20); uploads.add(u); return u; }, CreateMeshQueue.MAX_BYTES);
        try {
            queue.request("kinetic", () -> capture(8), m -> fail(), () -> {});
            queue.frame(); manager.tryRunAJob(); queue.frame();
            assertFalse(uploads.getFirst().done()); queue.cancel("kinetic");
            assertTrue(uploads.getFirst().closed); assertEquals(0, queue.stats().reservedBytes());
            queue.request("bogey", () -> capture(8), m -> fail(), () -> {});
            queue.frame(); manager.tryRunAJob(); queue.close();
            assertEquals(0, queue.stats().reservedBytes()); assertEquals(0, queue.stats().tasks());
        } finally { queue.close(); manager.shutdown(); }
    }

    @Test void closingWaitsForWorkerAndDoesNotPublishItsResult() throws Exception {
        var manager = new ServiceManager(n -> {}); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var queue = new CreateMeshQueue(manager, () -> 0L, cpu -> new Upload(0), CreateMeshQueue.MAX_BYTES,
                cpu -> { entered.countDown(); await(release); cpu.prepare(); });
        try (var workers = Executors.newFixedThreadPool(2)) {
            queue.request(1, () -> capture(8), m -> fail(), () -> {}); queue.frame();
            var running = workers.submit(manager::tryRunAJob); assertTrue(entered.await(10, TimeUnit.SECONDS));
            var closing = workers.submit(queue::close);
            assertThrows(java.util.concurrent.TimeoutException.class, () -> closing.get(50, TimeUnit.MILLISECONDS));
            release.countDown(); running.get(10, TimeUnit.SECONDS); closing.get(10, TimeUnit.SECONDS);
            assertEquals(0, queue.stats().reservedBytes());
        } finally { release.countDown(); queue.close(); manager.shutdown(); }
    }

    @Test void failingCancellationCallbackDoesNotLeakOtherJobsOrPreventWorkerShutdown() {
        var manager = new ServiceManager(n -> {});
        var queue = new CreateMeshQueue(manager, () -> 0L, cpu -> new Upload(0), CreateMeshQueue.MAX_BYTES);
        try {
            queue.request(1, () -> capture(8), m -> fail(), () -> { throw new IllegalStateException("cancel fixture"); });
            queue.request(2, () -> capture(8), m -> fail(), () -> {});
            queue.frame();
            assertTrue(queue.stats().reservedBytes() > 0);
            assertDoesNotThrow(queue::close);
            assertEquals(0, queue.stats().reservedBytes()); assertEquals(0, queue.stats().tasks());
        } finally { queue.close(); manager.shutdown(); }
    }

    @Test void preparationFailureReleasesSnapshotWithoutReplacingVisibleModel() {
        var manager = new ServiceManager(n -> {}); var abandoned = new AtomicInteger();
        try (var queue = new CreateMeshQueue(manager, () -> 0L, cpu -> {
            fail("failed mesh reached uploader"); return new Upload(0);
        }, CreateMeshQueue.MAX_BYTES, cpu -> { throw new IllegalStateException("prepare fixture"); })) {
            queue.request(1, () -> capture(8), m -> fail("failed mesh replaced visible model"), abandoned::incrementAndGet);
            queue.frame(); manager.tryRunAJob(); queue.frame();
            assertEquals(1, abandoned.get()); assertEquals(1, queue.stats().failed());
            assertEquals(0, queue.stats().tasks()); assertEquals(0, queue.stats().reservedBytes());
        } finally { manager.shutdown(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("worker wait timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
