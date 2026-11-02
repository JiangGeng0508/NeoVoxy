package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static me.cortex.voxy.testing.Reflect.get;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class VoxelIngestServiceTest {
    static final class Fixture implements AutoCloseable {
        final ServiceManager pool = new ServiceManager(n -> {});
        final WorldEngine world;
        final VoxelIngestService service;
        boolean stopped;
        Fixture() { this(section -> {}, IngestTaskQueue.HEAP_TARGET, IngestTaskQueue.TASK_TARGET); }
        Fixture(Consumer<LevelChunkSection> beforeConvert, long bytes, int tasks) {
            var storage = new SectionSerializationStorage(new MemoryStorageBackend());
            world = new WorldEngine(storage);
            world.setSaveCallback((w, section, nonBlocking, acquired) -> {
                storage.saveSection(section); section.setNotDirty(); return false;
            });
            service = new VoxelIngestService(pool, beforeConvert, bytes, tasks);
        }
        boolean offer(int x, LevelChunkSection source, DataLayer light, VoxelIngestService.IngestBatch batch) {
            return service.rawIngest0(world, null, source, x, 0, 0, light, null, batch);
        }
        int refs() throws Exception { return ((AtomicInteger) get(world, "refCount")).get(); }
        BlockState read(int x) {
            var section = world.acquire(0, x >> 1, 0, 0);
            try { return world.getMapper().getBlockStateFromBlockId(Mapper.getBlockId(section.get((x & 1) * 16))); }
            finally { section.release(); }
        }
        public void close() {
            if (!stopped) service.shutdown();
            pool.shutdown(); world.free();
        }
    }

    static VoxelIngestService.IngestBatch batch(List<Boolean> results) {
        var batch = VoxelIngestService.createBatch(b -> results.add(b.succeeded())); batch.await(1); return batch;
    }
    static LevelChunkSection stone() { return IngestSnapshotTest.section(Blocks.STONE.defaultBlockState()); }
    static DataLayer light() { var light = new DataLayer(); light.set(0, 0, 0, 7); return light; }

    @Test void repeatedUpdatesKeepLatestSnapshotReleaseOldRefsAndWriteFinalVoxels() throws Exception {
        try (var f = new Fixture()) {
            var source = stone();
            for (int i = 0; i < 100; i++) {
                source.setBlockState(0, 0, 0, i == 99 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState());
                assertTrue(f.offer(0, source, light(), null));
            }
            source.setBlockState(0, 0, 0, Blocks.AIR.defaultBlockState());
            assertEquals(1, f.refs()); assertEquals(1, f.service.getTaskCount());
            assertEquals(99, f.service.getStats().coalesced());
            assertEquals(4096, f.service.getStats().arrayBytes());
            assertEquals(0, f.pool.tryRunAJob()); assertEquals(1, f.pool.tryRunAJob());
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), f.read(0));
            assertEquals(0, f.refs()); assertEquals(0, f.service.getStats().arrayBytes());
            assertTrue(f.service.blockTillEmpty(0));
        }
    }

    @Test void supersededAndSuccessfulBatchesAreEachCompletedExactlyOnce() throws Exception {
        try (var f = new Fixture()) {
            var results = new ArrayList<Boolean>(); var first = batch(results); var latest = batch(results);
            assertTrue(f.offer(0, stone(), null, first));
            assertTrue(f.offer(0, IngestSnapshotTest.section(Blocks.GOLD_BLOCK.defaultBlockState()), null, latest));
            assertEquals(List.of(false), results); assertEquals(1, f.refs());
            assertEquals(0, f.pool.tryRunAJob());
            assertEquals(List.of(false, true), results); assertEquals(0, f.refs());
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), f.read(0));
        }
    }

    @Test void rejectedReplayableTaskLeavesBatchResponsibilityWithCallerAndCanRetryAfterDrain() throws Exception {
        try (var f = new Fixture(section -> {}, 2048, 10)) {
            assertTrue(f.offer(0, stone(), light(), null));
            var results = new ArrayList<Boolean>(); var rejected = batch(results);
            assertFalse(f.offer(2, stone(), light(), rejected));
            assertTrue(results.isEmpty()); assertEquals(1, f.refs());
            assertEquals(1, f.service.getStats().refused());
            rejected.taskDone(false); assertEquals(List.of(false), results);
            assertEquals(0, f.pool.tryRunAJob());
            assertTrue(f.offer(2, stone(), light(), batch(results)));
            assertEquals(0, f.pool.tryRunAJob());
            assertEquals(List.of(false, true), results); assertEquals(0, f.refs());
            assertSame(Blocks.STONE.defaultBlockState(), f.read(2));
        }
    }

    @Test void ordinaryUpdatesOverTargetAreNeverSilentlyRejected() throws Exception {
        try (var f = new Fixture(section -> {}, 1, 1)) {
            assertTrue(f.offer(0, stone(), light(), null));
            assertTrue(f.offer(2, IngestSnapshotTest.section(Blocks.GOLD_BLOCK.defaultBlockState()), light(), null));
            assertEquals(2, f.service.getTaskCount()); assertEquals(2, f.refs());
            assertEquals(2, f.service.getStats().overTarget()); assertTrue(f.service.getStats().pressure() > 1);
            assertEquals(0, f.pool.tryRunAJob()); assertEquals(0, f.pool.tryRunAJob());
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), f.read(2)); assertEquals(0, f.refs());
        }
    }

    @Test void sameSectionCannotWriteConcurrentlyButOtherSectionsCanProgress() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var first = new AtomicInteger();
        try (var f = new Fixture(section -> {
            if (first.getAndIncrement() == 0) {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
        }, 1 << 20, 100); var threads = Executors.newSingleThreadExecutor()) {
            assertTrue(f.offer(0, stone(), light(), null));
            var running = threads.submit(f.pool::tryRunAJob);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(f.offer(0, IngestSnapshotTest.section(Blocks.GOLD_BLOCK.defaultBlockState()), light(), null));
                assertEquals(1, f.service.getStats().running()); assertEquals(1, f.service.getStats().queued());
                assertEquals(4096, f.service.getStats().arrayBytes());
                assertFalse(f.service.blockTillEmpty(10));
                assertNotEquals(0, f.pool.tryRunAJob(), "pending version must not run before older writer finishes");
                assertTrue(f.offer(2, stone(), light(), null)); assertEquals(0, f.pool.tryRunAJob());
                assertSame(Blocks.STONE.defaultBlockState(), f.read(2));
            } finally { release.countDown(); }
            assertEquals(0, running.get(5, TimeUnit.SECONDS));
            assertEquals(0, f.pool.tryRunAJob());
            assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), f.read(0)); assertEquals(0, f.refs());
        }
    }

    @Test void shutdownCancelsPendingBatchAndRejectsLateOffersWithoutRefLeaks() throws Exception {
        try (var f = new Fixture()) {
            var results = new ArrayList<Boolean>();
            assertTrue(f.offer(0, stone(), light(), batch(results))); assertEquals(1, f.refs());
            f.service.shutdown(); f.stopped = true;
            assertEquals(List.of(false), results); assertEquals(0, f.refs());
            assertEquals(0, f.service.getStats().arrayBytes());
            var late = batch(results); assertFalse(f.offer(0, stone(), light(), late));
            assertEquals(List.of(false), results); late.taskDone(false);
            assertEquals(List.of(false, false), results); assertEquals(0, f.refs());
        }
    }

    @Test void conversionFailureReportsBatchFailureAndReturnsBytesAndReference() throws Exception {
        try (var f = new Fixture(section -> { throw new IllegalStateException("injected conversion failure"); }, 8192, 10)) {
            var results = new ArrayList<Boolean>();
            assertTrue(f.offer(0, stone(), light(), batch(results)));
            assertEquals(0, f.pool.tryRunAJob());
            assertEquals(List.of(false), results); assertEquals(0, f.refs());
            assertEquals(0, f.service.getTaskCount()); assertEquals(0, f.service.getStats().arrayBytes());
        }
    }

    @Test void captureFailureReturnsBothCaptureAndTaskReferencesWithoutTakingBatchOwnership() throws Exception {
        try (var f = new Fixture()) {
            var results = new ArrayList<Boolean>(); var rejected = batch(results);
            assertFalse(f.offer(0, null, null, rejected));
            assertEquals(0, f.refs()); assertEquals(0, f.service.getTaskCount());
            assertTrue(results.isEmpty()); rejected.taskDone(false);
            assertEquals(List.of(false), results);
        }
    }

    @Test void shutdownWaitsForRunningConversionAndCancelsItsPendingSuccessor() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var results = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
        try (var f = new Fixture(section -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        }, 8192, 10); var threads = Executors.newFixedThreadPool(2)) {
            assertTrue(f.offer(0, stone(), light(), batch(results)));
            var worker = threads.submit(f.pool::tryRunAJob);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(f.offer(0, stone(), light(), batch(results)));
            var closing = threads.submit(f.service::shutdown);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (results.isEmpty() && System.nanoTime() < deadline) Thread.yield();
                assertEquals(List.of(false), results);
                assertFalse(closing.isDone()); assertEquals(1, f.refs());
                assertEquals(1, f.service.getTaskCount()); assertFalse(f.service.blockTillEmpty(10));
            } finally { release.countDown(); }
            assertEquals(0, worker.get(5, TimeUnit.SECONDS)); closing.get(5, TimeUnit.SECONDS); f.stopped = true;
            assertEquals(List.of(false, true), results); assertEquals(0, f.refs());
            assertTrue(f.service.blockTillEmpty(0)); assertEquals(0, f.service.getStats().arrayBytes());
        }
    }

    @Test void chunklessLatestPayloadInheritsUnpersistedMaterialsButExplicitEmptyOrRemovedHolderWins() {
        var key = new IngestTaskQueue.Key(new Object(), 0, 0, 0);
        var holders = new long[64]; holders[0] = 1;
        var material = new me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.Material(Blocks.STONE.defaultBlockState(), -1);
        var tiles = new me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.SectionSnapshot(0, 0, 0,
                List.of(material), List.of(new me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.Cell(0, 0, 15)), holders);
        var oldData = new VoxelIngestService.IngestSection(0, 0, 0, null, null,
                new net.minecraft.world.level.block.entity.BlockEntity[1], stone(), null, null, tiles, null, 6);
        var old = new IngestTaskQueue.Task<>(key, oldData, 0, IngestSnapshot.compatibilityEstimate(1, tiles));
        var latestSection = IngestSnapshotTest.section(Blocks.GOLD_BLOCK.defaultBlockState());
        var results = new ArrayList<Boolean>(); var latestBatch = batch(results);
        var latestData = new VoxelIngestService.IngestSection(0, 0, 0, null, null,
                new net.minecraft.world.level.block.entity.BlockEntity[0], latestSection, light(), null, null, latestBatch, 6);
        var latest = new IngestTaskQueue.Task<>(key, latestData, 2048, 0);
        var merged = VoxelIngestService.mergePending(old, latest);
        assertSame(tiles, merged.value().littleTiles()); assertSame(oldData.domumBlockEntities(), merged.value().domumBlockEntities());
        assertSame(latestSection, merged.value().section()); assertSame(latestBatch, merged.value().batch());
        assertEquals(2048, merged.arrayBytes()); assertEquals(old.compatibilityBytes(), merged.compatibilityBytes());

        var empty = new me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.SectionSnapshot(0, 0, 0, List.of(), List.of(), new long[64]);
        var emptyData = new VoxelIngestService.IngestSection(0, 0, 0, null, null,
                new net.minecraft.world.level.block.entity.BlockEntity[0], latestSection, null, null, empty, null, 4);
        assertSame(empty, VoxelIngestService.mergePending(old, new IngestTaskQueue.Task<>(key, emptyData, 0, 512)).value().littleTiles());
        var removedData = new VoxelIngestService.IngestSection(0, 0, 0, null, null,
                new net.minecraft.world.level.block.entity.BlockEntity[0], stone(), null, null, null, null, 0);
        var removed = VoxelIngestService.mergePending(old, new IngestTaskQueue.Task<>(key, removedData, 0, 0));
        assertNull(removed.value().littleTiles()); assertEquals(0, removed.value().domumBlockEntities().length);
        assertEquals(0, removed.compatibilityBytes());
    }
}
