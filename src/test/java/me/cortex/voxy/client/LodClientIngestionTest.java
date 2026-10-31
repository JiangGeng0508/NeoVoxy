package me.cortex.voxy.client;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LodClientIngestionTest {
    static final String DIM = "minecraft:overworld";
    static WorldEngine world() { return savingWorld(new SectionSerializationStorage(new MemoryStorageBackend())); }
    static WorldEngine savingWorld(SectionSerializationStorage storage) {
        var world = new WorldEngine(storage);
        world.setSaveCallback((w, section, nonBlocking, acquired) -> {
            storage.saveSection(section); section.setNotDirty(); return false;
        });
        return world;
    }
    static long key(int x) { return WorldEngine.getWorldSectionId(0, x, 0, 0); }
    static LodSectionRequests.Request request(int x) { return new LodSectionRequests.Request(DIM, key(x)); }
    static VoxyNetwork.SectionDataS2C payload(int x, int version) {
        return new VoxyNetwork.SectionDataS2C(DIM, 0, x, 0, 0, new byte[]{(byte) version});
    }
    static VoxyNetwork.SectionDataS2C uniform(int x, long voxel) {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, x, 0, 0);
        section.setUniform(voxel); section.setKnownOctants(0x59); section._unsafeSetNonEmptyChildren((byte) 0xa5);
        var buf = SaveLoadSystem3.serialize(section).asByteBuffer();
        byte[] data = new byte[buf.remaining()]; buf.get(data);
        return new VoxyNetwork.SectionDataS2C(DIM, 0, x, 0, 0, data);
    }
    static class Processor implements LodClientIngestion.Processor {
        int decoded;
        boolean wait;
        Runnable duringDecode = () -> {};
        final List<Integer> writes = new ArrayList<>();
        public LodClientIngestion.Decoded decode(VoxyNetwork.SectionDataS2C p, LodClientIngestion.Snapshot maps) {
            decoded++; duringDecode.run();
            return LodClientIngestion.Decoded.result(wait && maps.revision() == 0
                    ? LodClientIngestion.Result.WAIT_FOR_MAPPINGS : p.data().length == 0
                    ? LodClientIngestion.Result.MISSING : LodClientIngestion.Result.APPLIED);
        }
        public LodClientIngestion.Snapshot mapping(WorldEngine w, LodClientIngestion.Snapshot s, Object p) {
            return new LodClientIngestion.Snapshot(new int[]{1}, new int[0], s.revision() + 1);
        }
        public void apply(WorldEngine w, VoxyNetwork.SectionDataS2C p, LodClientIngestion.Decoded d) { writes.add((int) p.data()[0]); }
    }
    static class Confirmations implements LodClientIngestion.Confirmations {
        final List<LodClientIngestion.Result> results = new ArrayList<>();
        final List<LodClientIngestion.Snapshot> snapshots = new ArrayList<>();
        public void section(LodSectionRequests.Request r, LodClientIngestion.Result result) { results.add(result); }
        public void mappings(String dim, LodClientIngestion.Snapshot s) { snapshots.add(s); }
    }

    @Test void coldReadsAndVoxelWritesRunOnlyOnTheWorkerAndConfirmationsWaitForClientTick() throws Exception {
        var thread = new AtomicReference<Thread>(); var reads = new AtomicInteger();
        var storage = new SectionSerializationStorage(new MemoryStorageBackend()) {
            @Override public int loadSection(WorldSection s) { reads.incrementAndGet(); thread.set(Thread.currentThread()); return super.loadSection(s); }
        };
        var world = savingWorld(storage); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, dim -> world, confirmations);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var p = uniform(1, Mapper.composeMappingId((byte) 15, 0, 0));
            assertTrue(queue.section(p, request(1))); assertEquals(0, reads.get()); assertTrue(world.isWorldUsed());
            assertEquals(0, executor.submit(manager::tryRunAJob).get(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), thread.get()); assertEquals(1, reads.get());
            assertTrue(confirmations.results.isEmpty()); assertFalse(world.isWorldUsed());
            assertEquals(1, queue.drain(Long.MAX_VALUE)); assertEquals(List.of(LodClientIngestion.Result.APPLIED), confirmations.results);
            var section = world.acquireIfExists(key(1));
            try { assertEquals(0x59, section.getKnownOctants()); assertEquals((byte) 0xa5, section.getNonEmptyChildren()); assertEquals(0, section.getNonEmptyBlockCount()); }
            finally { section.release(); }
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void queuedUpdatesCoalesceAndAnUndrainedOldConfirmationCannotCompleteTheLatestPayload() {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 2L << 20, 4, System::nanoTime);
        try {
            for (int version = 1; version <= 100; version++) assertTrue(queue.section(payload(1, version), request(1)));
            assertEquals(1, queue.stats().tasks()); assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(100), processor.writes);
            assertTrue(queue.section(payload(1, 101), request(1))); assertEquals(0, queue.drain(Long.MAX_VALUE));
            assertEquals(0, manager.tryRunAJob()); assertEquals(1, queue.drain(Long.MAX_VALUE));
            assertEquals(List.of(100, 101), processor.writes); assertEquals(1, confirmations.results.size());
            assertEquals(100, queue.stats().coalesced());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void runningUpdatesKeepOnlyOneLatestSuccessorAndDoNotPublishTheObsoleteSnapshot() {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 2L << 20, 4, System::nanoTime);
        try {
            processor.duringDecode = () -> {
                for (int version = 2; version <= 100; version++) assertTrue(queue.section(payload(1, version), request(1)));
            };
            queue.section(payload(1, 1), request(1)); assertEquals(0, manager.tryRunAJob());
            assertTrue(processor.writes.isEmpty()); assertEquals(1, queue.stats().tasks()); assertEquals(2, queue.stats().peakTasks());
            processor.duringDecode = () -> {};
            assertEquals(0, manager.tryRunAJob()); queue.drain(Long.MAX_VALUE);
            assertEquals(List.of(100), processor.writes); assertEquals(1, confirmations.results.size()); assertEquals(1, queue.stats().stale());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void capacityRefusalInvalidatesRunningWorkAndRetainsAnAuthoritativeBroadcastForPullRetry() {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 1L << 20, 1, System::nanoTime);
        var requests = new LodSectionRequests();
        try {
            requests.hold(request(1)); queue.section(payload(1, 1), request(1));
            processor.duringDecode = () -> {
                requests.hold(request(1)); assertFalse(queue.section(payload(1, 2), request(1))); requests.retry(request(1), 0);
            };
            manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertTrue(processor.writes.isEmpty()); assertTrue(confirmations.results.isEmpty());
            var sent = new ArrayList<LodSectionRequests.Request>(); requests.pump(DIM, LodSectionRequests.RETRY_NANOS, sent::add);
            assertEquals(List.of(request(1)), sent); assertFalse(world.isWorldUsed());
            assertTrue(queue.stats().peakBytes() <= 1L << 20); assertEquals(1, queue.stats().peakTasks());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void missingMappingsWaitWithoutPollingAndWakeOnMappingPublication() {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); processor.wait = true;
        var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 2L << 20, 4, System::nanoTime);
        try {
            queue.section(payload(1, 1), request(1)); assertEquals(0, manager.tryRunAJob());
            for (int i = 0; i < 100; i++) assertEquals(1, manager.tryRunAJob());
            assertEquals(1, processor.decoded); assertEquals(1, queue.stats().waiting()); assertTrue(queue.needsMappings());
            assertTrue(world.isWorldUsed());
            queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(0, List.of(new byte[]{1})));
            assertEquals(0, manager.tryRunAJob()); assertEquals(0, manager.tryRunAJob()); queue.drain(Long.MAX_VALUE);
            assertEquals(List.of(1), processor.writes); assertFalse(queue.needsMappings()); assertFalse(world.isWorldUsed());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void realMappingPagesPublishImmutableSnapshotsAndDecodeBeforeWritingAllVoxels() {
        var world = world(); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations);
        try {
            var p = uniform(1, Mapper.composeMappingId((byte) 15, 10, 0));
            queue.section(p, request(1)); manager.tryRunAJob(); assertEquals(1, queue.stats().waiting());
            var state = new Mapper.StateEntry(10, Blocks.STONE.defaultBlockState());
            queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(10, List.of(state.serialize())));
            queue.mapping(DIM, new VoxyNetwork.BiomeMapBatchS2C(0, List.of("example:forest")));
            while (manager.tryRunAJob() == 0) {} queue.drain(Long.MAX_VALUE);
            var previous = confirmations.snapshots.getFirst(); assertEquals(0, previous.biomes().length);
            var section = world.acquireIfExists(key(1));
            try {
                int stone = world.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
                for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                    assertEquals(stone, Mapper.getBlockId(section.get(i))); assertEquals(15, Mapper.getLightId(section.get(i)));
                }
                assertEquals(WorldSection.SECTION_VOLUME, section.getNonEmptyBlockCount());
            } finally { section.release(); }
            queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(10, List.of(new Mapper.StateEntry(10, Blocks.DIRT.defaultBlockState()).serialize())));
            manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertNotEquals(previous.blocks()[10], confirmations.snapshots.getLast().blocks()[10]);
            assertEquals(0, previous.biomes().length);
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void malformedPaletteCannotChangeLiveDataAndTransientFailureRemainsRetryable() {
        var world = world(); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations);
        try {
            var p = uniform(1, 0);
            ByteBuffer.wrap(p.data()).order(ByteOrder.nativeOrder()).putShort(16 + 100 * 2, (short) 1);
            queue.section(p, request(1)); manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertEquals(List.of(LodClientIngestion.Result.RETRY), confirmations.results); assertEquals(1, queue.stats().failures());
            assertFalse(world.isWorldUsed());
            queue.section(uniform(1, 0), request(1)); manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertEquals(LodClientIngestion.Result.APPLIED, confirmations.results.getLast());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void emptyResultsAreEntryBoundedUntilConfirmationAndDrainHonorsItsTimeBudget() {
        var world = world(); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var time = new AtomicLong();
        var queue = new LodClientIngestion(manager, d -> world, confirmations, new Processor(), 1L << 20, 4, () -> time.getAndAdd(250_000));
        try {
            for (int i = 0; i < 4; i++) {
                assertTrue(queue.section(new VoxyNetwork.SectionDataS2C(DIM, 0, i, 0, 0, new byte[0]), request(i)));
                manager.tryRunAJob();
            }
            assertFalse(queue.section(payload(5, 1), request(5))); assertEquals(0, queue.stats().bytes());
            assertEquals(3, queue.drain(LodClientIngestion.CONFIRM_NANOS)); assertEquals(1, queue.stats().tasks());
            queue.drain(Long.MAX_VALUE); assertEquals(4, confirmations.results.size()); assertFalse(world.isWorldUsed());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void closeWaitsForRunningDecodeAndBlocksOldWritesAndConfirmationsBeforeTheNextSession() throws Exception {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); var confirmations = new Confirmations();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        processor.duringDecode = () -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        };
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 2L << 20, 4, System::nanoTime);
        try (var executor = Executors.newFixedThreadPool(2)) {
            queue.section(payload(1, 1), request(1)); queue.section(payload(2, 1), request(2));
            var running = executor.submit(manager::tryRunAJob); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopping = executor.submit(queue::close);
            assertThrows(TimeoutException.class, () -> stopping.get(100, TimeUnit.MILLISECONDS));
            finish.countDown(); running.get(5, TimeUnit.SECONDS); stopping.get(5, TimeUnit.SECONDS);
            assertTrue(processor.writes.isEmpty()); assertTrue(confirmations.results.isEmpty()); assertFalse(world.isWorldUsed());
            assertEquals(0, queue.stats().tasks()); assertEquals(0, queue.drain(Long.MAX_VALUE));
            var next = new LodClientIngestion(manager, d -> world, confirmations, new Processor(), 2L << 20, 4, System::nanoTime);
            try { assertTrue(next.section(payload(1, 2), request(1))); manager.tryRunAJob(); assertEquals(1, next.drain(Long.MAX_VALUE)); }
            finally { next.close(); }
        } finally { finish.countDown(); queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void fullPayloadBudgetStillAdmitsTheMappingNeededToReleaseWaitingWork() {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); processor.wait = true;
        var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 2L << 20, 16, System::nanoTime);
        try {
            assertTrue(queue.section(payload(1, 1), request(1))); manager.tryRunAJob();
            assertTrue(queue.section(payload(2, 1), request(2))); manager.tryRunAJob();
            assertFalse(queue.section(payload(3, 1), request(3)));
            assertTrue(queue.pressured());
            assertTrue(queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(0, List.of(new byte[]{1}))));
            while (manager.tryRunAJob() == 0) {} queue.drain(Long.MAX_VALUE);
            assertEquals(2, confirmations.results.size()); assertEquals(0, queue.stats().waiting());
            assertFalse(queue.pressured());
            assertTrue(queue.stats().peakBytes() <= 2L << 20);
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void mappingReservationsBoundSnapshotsAndRetiredWorldTablesAreReleased() {
        var world = world(); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var current = new AtomicReference<>(world);
        var queue = new LodClientIngestion(manager, d -> current.get(), confirmations,
                new LodClientIngestion.WorldProcessor(), 1L << 20, 16, System::nanoTime);
        WorldEngine next = null;
        try {
            var state = new Mapper.StateEntry(65535, Blocks.STONE.defaultBlockState());
            assertTrue(queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(65535, List.of(state.serialize()))));
            manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertEquals(2L * 65536 * 4, queue.stats().bytes());
            assertFalse(queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(65536, List.of(new Mapper.StateEntry(65536, Blocks.DIRT.defaultBlockState()).serialize()))));
            assertTrue(queue.needsMappings()); assertTrue(queue.stats().peakBytes() <= 1L << 20);
            world.free(); next = world(); current.set(next);
            assertTrue(queue.section(payload(1, 1), request(1))); // Prunes retired mapping tables before admission.
            assertTrue(queue.stats().bytes() < 600000);
        } finally { queue.close(); manager.shutdown(); if (world.isLive()) world.free(); if (next != null) next.free(); }
    }

    @Test void recreatedWorldsDoNotReuseAnotherEnginesLocalMappingIds() {
        var a = world(); var b = world(); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var current = new AtomicReference<>(a);
        var queue = new LodClientIngestion(manager, d -> current.get(), confirmations);
        try {
            queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(10, List.of(new Mapper.StateEntry(10, Blocks.STONE.defaultBlockState()).serialize())));
            queue.mapping(DIM, new VoxyNetwork.BiomeMapBatchS2C(0, List.of("example:forest")));
            while (manager.tryRunAJob() == 0) {} queue.drain(Long.MAX_VALUE);
            current.set(b);
            queue.section(uniform(1, Mapper.composeMappingId((byte) 15, 10, 0)), request(1)); manager.tryRunAJob();
            assertEquals(1, queue.stats().waiting()); assertTrue(confirmations.results.isEmpty());
            queue.mapping(DIM, new VoxyNetwork.BlockMapBatchS2C(10, List.of(new Mapper.StateEntry(10, Blocks.DIRT.defaultBlockState()).serialize())));
            queue.mapping(DIM, new VoxyNetwork.BiomeMapBatchS2C(0, List.of("example:desert")));
            while (manager.tryRunAJob() == 0) {} queue.drain(Long.MAX_VALUE);
            var section = b.acquireIfExists(key(1));
            try { assertEquals(b.getMapper().getIdForBlockState(Blocks.DIRT.defaultBlockState()), Mapper.getBlockId(section.get(0))); }
            finally { section.release(); }
            assertEquals(List.of(LodClientIngestion.Result.APPLIED), confirmations.results);
        } finally { queue.close(); manager.shutdown(); a.free(); b.free(); }
    }

    @Test void multiplePoolThreadsCannotRunTwoConsumersWhileTheFirstDecodeIsBlocked() throws Exception {
        var world = world(); var manager = new ServiceManager(n -> {}); var processor = new Processor(); var confirmations = new Confirmations();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var calls = new AtomicInteger();
        processor.duringDecode = () -> {
            if (calls.incrementAndGet() != 1) return;
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        };
        var queue = new LodClientIngestion(manager, d -> world, confirmations, processor, 2L << 20, 4, System::nanoTime);
        try (var executor = Executors.newFixedThreadPool(2)) {
            queue.section(payload(1, 1), request(1)); queue.section(payload(2, 2), request(2));
            var first = executor.submit(manager::tryRunAJob); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertNotEquals(0, executor.submit(manager::tryRunAJob).get(5, TimeUnit.SECONDS)); assertEquals(1, calls.get());
            finish.countDown(); assertEquals(0, first.get(5, TimeUnit.SECONDS));
            assertEquals(0, executor.submit(manager::tryRunAJob).get(5, TimeUnit.SECONDS));
            queue.drain(Long.MAX_VALUE); assertEquals(List.of(1, 2), processor.writes);
        } finally { finish.countDown(); queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void transientColdReadFailureReleasesConcurrentReadersAndTheSameKeyCanBeRetried() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1); var reads = new AtomicInteger();
        var storage = new SectionSerializationStorage(new MemoryStorageBackend()) {
            @Override public int loadSection(WorldSection section) {
                if (reads.incrementAndGet() == 1) {
                    entered.countDown();
                    try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    throw new IllegalStateException("injected transient cold read failure");
                }
                return super.loadSection(section);
            }
        };
        var world = savingWorld(storage); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var queue = new LodClientIngestion(manager, d -> world, confirmations);
        try (var executor = Executors.newFixedThreadPool(2)) {
            queue.section(uniform(1, 0), request(1));
            var processing = executor.submit(manager::tryRunAJob); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var reader = executor.submit(() -> world.acquire(key(1)));
            assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
            finish.countDown(); assertEquals(0, processing.get(5, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> reader.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause()); assertEquals(1, reads.get());
            queue.drain(Long.MAX_VALUE); assertEquals(List.of(LodClientIngestion.Result.RETRY), confirmations.results);
            assertFalse(world.isWorldUsed());
            queue.section(uniform(1, 0), request(1)); manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertEquals(2, reads.get()); assertEquals(LodClientIngestion.Result.APPLIED, confirmations.results.getLast());
            assertEquals(1, queue.stats().failures());
        } finally { finish.countDown(); queue.close(); manager.shutdown(); world.free(); }
    }

    @Test void failureAfterWritingVoxelsDoesNotAcknowledgeAndRetryPublishesAndPersistsCoverage() {
        var world = world(); var manager = new ServiceManager(n -> {}); var confirmations = new Confirmations();
        var notifications = new AtomicInteger();
        world.setDirtyCallback((section, flags, neighbors) -> {
            if (notifications.getAndIncrement() == 0) throw new IllegalStateException("injected publication failure");
        });
        var queue = new LodClientIngestion(manager, d -> world, confirmations);
        try {
            queue.section(uniform(1, 0), request(1)); manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertEquals(List.of(LodClientIngestion.Result.RETRY), confirmations.results);
            assertFalse(world.isWorldUsed());
            queue.section(uniform(1, 0), request(1)); manager.tryRunAJob(); queue.drain(Long.MAX_VALUE);
            assertEquals(List.of(LodClientIngestion.Result.RETRY, LodClientIngestion.Result.APPLIED), confirmations.results);
            var saved = WorldSection._createRawUntrackedUnsafeSection(0, 1, 0, 0);
            assertEquals(0, world.storage.loadSection(saved)); assertEquals(0x59, saved.getKnownOctants());
            assertEquals((byte) 0xa5, saved.getNonEmptyChildren()); assertEquals(1, queue.stats().failures());
        } finally { queue.close(); manager.shutdown(); world.free(); }
    }
}
