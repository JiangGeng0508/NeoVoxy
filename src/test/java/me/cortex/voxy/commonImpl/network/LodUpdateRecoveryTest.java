package me.cortex.voxy.commonImpl.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import static me.cortex.voxy.commonImpl.network.LodServerSchedulerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LodUpdateRecoveryTest {
    @TempDir Path directory;

    @Test void coalescesAndKeepsDirtiesRacingOldAcknowledgements() {
        var q = new LodUpdateQueue(2);
        var player = UUID.randomUUID();
        q.dirty(key(0));
        var old = q.next(player, k -> true, k -> Set.of(player));
        for (int i = 0; i < 10000; i++) q.dirty(key(0));
        q.complete(player, old);
        assertEquals(1, q.stats().pending());
        assertEquals(10000, q.stats().coalesced());
        q.dirty(key(1));
        assertFalse(q.dirty(key(2)));
        long repair = q.repairs().get(DIM);
        assertFalse(q.dirty(key(3)));
        q.repaired(DIM, repair);
        assertEquals(1, q.stats().repairs(), "new overflow must survive an older sweep");
        var latest = q.next(player, k -> true, k -> Set.of(player));
        q.complete(player, latest);
        assertEquals(1, q.stats().pending());
        assertEquals(2, q.stats().peak());
    }

    @Test void recoveryLeavesCapacityForLiveUpdatesInsteadOfTriggeringAnotherSweep() throws Exception {
        var q = new LodUpdateQueue(4);
        var player = UUID.randomUUID();
        q.recover(key(0)); q.recover(key(1));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> { q.recover(key(2)); return true; });
            assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS));
            assertTrue(q.dirty(key(3))); assertTrue(q.dirty(key(4)));
            assertEquals(0, q.stats().overflow());
            for (int i = 0; i < 3; i++) {
                var ticket = q.next(player, k -> true, k -> Set.of(player));
                assertNotNull(ticket); q.complete(player, ticket);
            }
            assertTrue(future.get(5, TimeUnit.SECONDS));
            assertEquals(0, q.stats().repairs());
            assertEquals(2, q.stats().pending());
        } finally { q.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test void concurrentProducersKeepOneLatestNotificationPerKey() throws Exception {
        var q = new LodUpdateQueue(256);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int thread = 0; thread < 8; thread++) futures.add(executor.submit(() -> {
                for (int i = 0; i < 4096; i++) assertTrue(q.dirty(key(i % 256)));
            }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(256, q.stats().pending());
        assertEquals(8 * 4096 - 256, q.stats().coalesced());
        var player = UUID.randomUUID();
        var seen = new java.util.HashSet<Long>();
        for (int i = 0; i < 256; i++) {
            var ticket = q.next(player, k -> true, k -> Set.of(player));
            assertNotNull(ticket); assertTrue(seen.add(ticket.key().section())); q.complete(player, ticket);
        }
        assertEquals(0, q.stats().pending());
    }

    @Test void worldSweepIncludesUnsavedActiveAndPersistentSections() {
        var storage = new SectionSerializationStorage(new RocksDBStorageBackend(directory.resolve("world").toString()));
        var engine = new WorldEngine(storage);
        var active = engine.acquire(0, -17, 2, 33);
        try {
            active.setUniform(15L << 56);
            var saved = WorldSection._createRawUntrackedUnsafeSection(3, 12, -2, -80);
            saved.setUniform(15L << 56);
            storage.saveSection(saved);
            var known = new java.util.HashSet<Long>();
            engine.iterateKnownPositions(known::add);
            assertEquals(Set.of(active.key, saved.key), known);
            assertEquals(1, storage.loadSection(WorldSection._createRawUntrackedUnsafeSection(0, -17, 2, 33)),
                    "active key must be included even before it reaches storage");
        } finally { active.release(); engine.free(); }
    }

    @Test void recoveryReleasesStoreIteratorBeforeWaitingAndDeletesSpool() throws Exception {
        var q = new LodUpdateQueue(1);
        var player = UUID.randomUUID();
        q.dirty(key(0)); q.dirty(key(1));
        long revision = q.repairs().get(DIM);
        var released = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> LodUpdateRecovery.sweep(directory, DIM, revision, q, consumer -> {
                for (int i = 1; i <= 256; i++) consumer.accept(key(i).section());
                released.countDown();
            }, k -> true));
            assertTrue(released.await(5, TimeUnit.SECONDS), "store iterator must not wait on dispatch capacity");
            var received = new java.util.HashSet<Long>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!future.isDone() || q.stats().pending() != 0) {
                assertTrue(System.nanoTime() < deadline, "recovery stalled");
                var ticket = q.next(player, k -> true, k -> Set.of(player));
                if (ticket != null) { received.add(ticket.key().section()); q.complete(player, ticket); }
                else Thread.yield();
            }
            assertEquals(256 * 8, future.get(5, TimeUnit.SECONDS));
            assertEquals(257, received.size());
            assertEquals(0, q.stats().repairs());
            assertEquals(1, q.stats().peak());
        } finally { q.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    @Test void shutdownUnblocksRecoveryAndFailedScanKeepsRepairMarker() throws Exception {
        var q = new LodUpdateQueue(1);
        q.dirty(key(0)); q.dirty(key(1));
        var executor = Executors.newSingleThreadExecutor();
        var captured = new CountDownLatch(1);
        try {
            var future = executor.submit(() -> LodUpdateRecovery.sweep(directory, DIM, q.repairs().get(DIM), q,
                    consumer -> { consumer.accept(key(1).section()); captured.countDown(); }, k -> true));
            assertTrue(captured.await(5, TimeUnit.SECONDS));
            q.close();
            assertInstanceOf(InterruptedException.class, assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS)).getCause());
        } finally { q.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        var broken = new LodUpdateQueue(1);
        broken.dirty(key(0)); broken.dirty(key(1));
        assertThrows(IllegalStateException.class, () -> LodUpdateRecovery.sweep(directory, DIM,
                broken.repairs().get(DIM), broken, c -> { throw new IllegalStateException("injected scan failure"); }, k -> true));
        assertEquals(1, broken.stats().repairs());
        try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
    }
}
