package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MeshResultBudgetTest {
    static MeshResultBudget singleSlot() {
        return new MeshResultBudget(MeshResultBudget.BUILD_NATIVE_BYTES, MeshResultBudget.BUILD_HEAP_BYTES, 1);
    }

    static BuiltSection mesh(long position) {
        return new BuiltSection(position, (byte) 0, 0, new MemoryBuffer(64).zero(), new int[8], new MemoryBuffer(8).zero());
    }

    @Test void buildReservationShrinksToActualBytesAndLivesUntilResultFree() {
        long initial = MemoryBuffer.getTotalSize();
        var budget = singleSlot();
        var reservation = budget.tryReserve();
        assertNotNull(reservation);
        assertFalse(budget.canReserve());
        assertNull(budget.tryReserve());
        assertEquals(1, budget.stats().building());
        assertEquals(MeshResultBudget.BUILD_NATIVE_BYTES, budget.stats().nativeBytes());
        var result = mesh(1);
        result.retainReservation(reservation);
        assertEquals(72, budget.stats().nativeBytes());
        assertEquals(72, budget.stats().resultNativeBytes());
        assertEquals(32, budget.stats().resultHeapBytes());
        assertEquals(0, budget.stats().building());
        assertFalse(budget.canReserve(), "a small result still occupies a bounded entry");
        result.free();
        result.free();
        assertEquals(0, budget.stats().entries());
        assertEquals(0, budget.stats().nativeBytes());
        assertEquals(0, budget.stats().heapBytes());
        assertTrue(budget.canReserve());
        assertEquals(initial, MemoryBuffer.getTotalSize());
    }

    @Test void nativeHeapAndEntryLimitsIndependentlyPreventAdmission() {
        var nativeBudget = new MeshResultBudget(MeshResultBudget.BUILD_NATIVE_BYTES * 2, 1024, 100);
        var a = nativeBudget.tryReserve(); var b = nativeBudget.tryReserve();
        assertNotNull(a); assertNotNull(b); assertNull(nativeBudget.tryReserve());
        assertEquals(1, nativeBudget.stats().pressure());
        a.close(); b.close();

        var heapBudget = new MeshResultBudget(MeshResultBudget.BUILD_NATIVE_BYTES * 10, 32, 100);
        var c = heapBudget.tryReserve();
        c.retain(0, 32);
        assertNull(heapBudget.tryReserve());
        assertEquals(1, heapBudget.stats().pressure());
        c.close();

        var entries = singleSlot();
        var empty = BuiltSection.empty(0);
        empty.retainReservation(entries.tryReserve());
        assertEquals(0, entries.stats().nativeBytes());
        assertNull(entries.tryReserve());
        empty.free();
        assertTrue(entries.canReserve());
    }

    @Test void failedTransferKeepsReservationForCallerCleanup() {
        var budget = singleSlot();
        var reservation = budget.tryReserve();
        assertThrows(IllegalArgumentException.class,
                () -> reservation.retain(MeshResultBudget.BUILD_NATIVE_BYTES + 1, 0));
        assertEquals(1, budget.stats().building());
        reservation.close(); reservation.close();
        assertEquals(0, budget.stats().nativeBytes());
        assertEquals(0, budget.stats().entries());
    }

    @Test void concurrentAdmissionNeverExceedsReservationOrEntryLimits() throws Exception {
        var budget = new MeshResultBudget(MeshResultBudget.BUILD_NATIVE_BYTES * 2, 64, 2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) futures.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 1000; i++) {
                    var lease = budget.tryReserve();
                    if (lease == null) { Thread.yield(); continue; }
                    try {
                        var stats = budget.stats();
                        assertTrue(stats.nativeBytes() <= stats.nativeLimit());
                        assertTrue(stats.heapBytes() <= stats.heapLimit());
                        assertTrue(stats.entries() <= stats.entryLimit());
                        lease.retain(64, 32);
                    } finally { lease.close(); }
                }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, budget.stats().entries());
        assertEquals(0, budget.stats().nativeBytes());
        assertEquals(0, budget.stats().resultNativeBytes());
        assertEquals(0, budget.stats().heapBytes());
    }

    @Test void pendingGeometryOwnsCompleteResultsUntilCopyReplacementRemovalOrShutdown() {
        long initial = MemoryBuffer.getTotalSize();
        var budget = new MeshResultBudget();
        var geometry = new BasicAsyncGeometryManager(16, 64L << 20);
        var first = mesh(1); first.retainReservation(budget.tryReserve());
        int id = geometry.uploadSection(first);
        assertEquals(1, budget.stats().entries(), "node adoption must not release the native budget");
        var replacement = mesh(1); replacement.retainReservation(budget.tryReserve());
        id = geometry.uploadReplaceSection(id, replacement);
        assertEquals(1, budget.stats().entries(), "replacing an un-copied upload frees its full result");
        geometry.removeSection(id);
        assertEquals(0, budget.stats().entries());
        assertEquals(initial, MemoryBuffer.getTotalSize(), "occupancy must be freed along with geometry");

        var copied = mesh(2); copied.retainReservation(budget.tryReserve());
        geometry.uploadSection(copied);
        try (var ignored = new CopyTarget()) {
            for (var result : geometry.getUploads().values()) {
                result.geometryBuffer.cpyTo(ignored.buffer.address);
                assertEquals(1, budget.stats().entries());
                result.free();
            }
            geometry.getUploads().clear();
        }
        assertEquals(0, budget.stats().nativeBytes());
        var pending = mesh(3); pending.retainReservation(budget.tryReserve());
        geometry.uploadSection(pending);
        geometry.freePendingUploads(); geometry.freePendingUploads();
        assertEquals(0, budget.stats().entries());
        assertEquals(initial, MemoryBuffer.getTotalSize());
    }

    private static final class CopyTarget implements AutoCloseable {
        final MemoryBuffer buffer = new MemoryBuffer(64);
        public void close() { buffer.free(); }
    }

    @Test void nodeDiscardAndEmptyReplacementReleaseResultReservations() {
        var budget = new MeshResultBudget();
        var geometry = new BasicAsyncGeometryManager(16, 64L << 20);
        var router = new SectionUpdateRouter();
        router.setCallbacks(key -> {}, key -> {}, section -> {});
        var nodes = new NodeManager(16, geometry, router);
        long pos = WorldEngine.getWorldSectionId(4, 0, 0, 0);
        var discarded = mesh(pos); discarded.retainReservation(budget.tryReserve());
        nodes.processGeometryResult(discarded);
        assertEquals(0, budget.stats().entries());

        nodes.insertTopLevelNode(pos);
        var first = mesh(pos); first.retainReservation(budget.tryReserve());
        nodes.processGeometryResult(first);
        assertEquals(1, geometry.getSectionCount());
        assertEquals(1, budget.stats().entries());
        var empty = BuiltSection.empty(pos); empty.retainReservation(budget.tryReserve());
        nodes.processGeometryResult(empty);
        assertEquals(0, geometry.getSectionCount());
        assertEquals(0, budget.stats().entries(), "both removed native geometry and empty update must be released");
        var stillEmpty = BuiltSection.empty(pos); stillEmpty.retainReservation(budget.tryReserve());
        nodes.processGeometryResult(stillEmpty);
        assertEquals(0, budget.stats().entries());
        nodes.removeTopLevelNode(pos);
        geometry.freePendingUploads();
    }
}
