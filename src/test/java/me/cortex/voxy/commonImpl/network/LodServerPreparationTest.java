package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static me.cortex.voxy.commonImpl.network.LodServerSchedulerTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LodServerPreparationTest {
    static WorldEngine world() { return new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend())); }
    static class Loader implements LodServerPreparation.Loader {
        int loads, bytes = 327696, version = 1;
        Runnable duringLoad = () -> {};
        public VoxyNetwork.SectionDataS2C section(LodServerPreparation.SectionKey key) {
            loads++;
            var bytes = new byte[this.bytes];
            if (bytes.length > 0) bytes[0] = (byte) version;
            var data = new VoxyNetwork.SectionDataS2C(key.dimension(), 0, WorldEngine.getX(key.section()), 0, 0, bytes);
            duringLoad.run();
            return data;
        }
        public Object mapping(LodServerPreparation.MappingKey key) { return new LodServerPreparation.WorldLoader().mapping(key); }
    }

    @Test void coldReadOnlyRunsOnAWorkerAndDuplicateRequestsShareOneDetachedSnapshot() throws Exception {
        var readingThread = new AtomicReference<Thread>();
        var reads = new AtomicInteger();
        var storage = new SectionSerializationStorage(new MemoryStorageBackend()) {
            @Override public int loadSection(WorldSection into) {
                readingThread.set(Thread.currentThread()); reads.incrementAndGet();
                return super.loadSection(into);
            }
        };
        var world = new WorldEngine(storage); var manager = new ServiceManager(n -> {});
        var prep = new LodServerPreparation(manager);
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (int i = 0; i < 100; i++) assertNull(prep.section(world, key(1)));
            assertEquals(0, reads.get()); assertEquals(1, prep.stats().inFlight());
            assertTrue(world.isWorldUsed(), "queued preparation must retain the world");
            assertEquals(0, executor.submit(manager::tryRunAJob).get(5, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), readingThread.get()); assertEquals(1, reads.get());
            var data = prep.section(world, key(1));
            assertNotNull(data); assertEquals(0, data.data().length);
            assertSame(data, prep.section(world, key(1)));
            assertFalse(world.isWorldUsed(), "completed preparation releases its world ref");
            assertEquals(1, prep.stats().sectionsPrepared()); assertTrue(prep.current(world, key(1), data));
        } finally { executor.shutdownNow(); prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void newerDirtyDuringPreparationDiscardsResultAndRetainsRetry() {
        var world = world(); var manager = new ServiceManager(n -> {}); var loader = new Loader();
        var prep = new LodServerPreparation(manager, loader, 2L << 20, 4, 8);
        try {
            prep.section(world, key(1));
            loader.duringLoad = () -> { loader.version = 2; prep.invalidate(world, key(1)); };
            assertEquals(0, manager.tryRunAJob());
            assertEquals(1, prep.stats().invalidatedResults()); assertEquals(0, prep.stats().cachedSections());
            assertFalse(world.isWorldUsed());
            loader.duringLoad = () -> {};
            assertNull(prep.section(world, key(1))); assertEquals(0, manager.tryRunAJob());
            var data = prep.section(world, key(1)); assertEquals(2, data.data()[0]);
            prep.invalidate(world, key(1));
            assertFalse(prep.current(world, key(1), data));
            assertNull(prep.section(world, key(1)));
        } finally { prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void dirtiesBeforeTheWorkerStartsCoalesceIntoOnePreparation() {
        var world = world(); var manager = new ServiceManager(n -> {}); var loader = new Loader();
        var prep = new LodServerPreparation(manager, loader, 2L << 20, 4, 8);
        try {
            prep.section(world, key(1));
            for (int i = 0; i < 100; i++) { loader.version = 3; prep.invalidate(world, key(1)); assertNull(prep.section(world, key(1))); }
            assertEquals(1, prep.stats().inFlight()); assertEquals(0, manager.tryRunAJob());
            assertEquals(1, loader.loads); assertEquals(3, prep.section(world, key(1)).data()[0]);
            assertEquals(0, prep.stats().invalidatedResults());
        } finally { prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void reservationsBoundRunningAndCachedBytesAndOnlyEvictReadyData() {
        var world = world(); var manager = new ServiceManager(n -> {}); var loader = new Loader();
        var prep = new LodServerPreparation(manager, loader, 1L << 20, 2, 4);
        try {
            prep.section(world, key(1)); prep.section(world, key(2));
            assertNull(prep.section(world, key(3))); assertEquals(2, prep.stats().inFlight());
            assertEquals(1, prep.stats().refusals());
            assertEquals(0, manager.tryRunAJob()); assertEquals(0, manager.tryRunAJob());
            var first = prep.section(world, key(1)); var second = prep.section(world, key(2));
            assertNotNull(first); assertNotNull(second);
            prep.section(world, key(3)); assertEquals(0, manager.tryRunAJob());
            prep.section(world, key(4));
            assertFalse(prep.current(world, key(1), first)); assertTrue(prep.current(world, key(2), second));
            assertEquals(0, manager.tryRunAJob());
            assertTrue(prep.stats().peakBytes() <= 1L << 20); assertEquals(2, prep.stats().peakInFlight());
        } finally { prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void emptyRepliesAreEntryBoundedAndWorldIdentityIsPartOfTheCacheKey() {
        var a = world(); var b = world(); var manager = new ServiceManager(n -> {}); var loader = new Loader(); loader.bytes = 0;
        var prep = new LodServerPreparation(manager, loader, 1L << 20, 2, 2);
        try {
            prep.section(a, key(1)); prep.section(b, key(1));
            assertEquals(0, manager.tryRunAJob()); assertEquals(0, manager.tryRunAJob());
            var old = prep.section(a, key(1)); var other = prep.section(b, key(1));
            assertNotSame(old, other); assertFalse(prep.current(b, key(1), old));
            prep.section(a, key(2)); assertEquals(0, manager.tryRunAJob());
            assertEquals(2, prep.stats().cachedSections()); assertEquals(0, prep.stats().bytes());
            assertFalse(prep.current(a, key(1), old));
        } finally { prep.close(); manager.shutdown(); a.free(); b.free(); }
    }

    @Test void readFailureNeverCreatesAnEmptyReplyAndReleasesItsWorldReference() {
        var world = world(); var manager = new ServiceManager(n -> {}); var loader = new Loader();
        var prep = new LodServerPreparation(manager, loader, 2L << 20, 4, 8);
        try {
            loader.duringLoad = () -> { throw new IllegalStateException("injected cold read failure"); };
            prep.section(world, key(1)); assertEquals(0, manager.tryRunAJob());
            assertEquals(1, prep.stats().failures()); assertEquals(0, prep.stats().cachedSections());
            assertFalse(world.isWorldUsed());
            loader.duringLoad = () -> {};
            assertNull(prep.section(world, key(1))); assertEquals(0, manager.tryRunAJob());
            assertNotNull(prep.section(world, key(1)));
        } finally { prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void sharedMappingPagesArePreencodedAndAppendOnlyCursorsRemainCurrent() {
        var world = world(); var manager = new ServiceManager(n -> {}); var prep = new LodServerPreparation(manager);
        try {
            var mapper = world.getMapper();
            mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()); mapper.getIdForBlockState(Blocks.DIRT.defaultBlockState());
            mapper.getOrCreateBiomeId("example:林地");
            var pending = prep.mappings(world, 0, 0);
            assertNotNull(pending); assertNull(pending.payload()); assertEquals(0, pending.blockEnd());
            assertEquals(0, manager.tryRunAJob());
            var page = prep.mappings(world, 0, 0);
            var blocks = assertInstanceOf(VoxyNetwork.BlockMapBatchS2C.class, page.payload());
            assertNotNull(blocks.encoded()); assertEquals(3, blocks.entries().size());
            assertSame(blocks, prep.mappings(world, 0, 0).payload());
            assertNull(prep.mappings(world, 3, 0).payload()); assertEquals(0, manager.tryRunAJob());
            var biomePage = prep.mappings(world, 3, 0);
            var biomes = assertInstanceOf(VoxyNetwork.BiomeMapBatchS2C.class, biomePage.payload());
            assertNotNull(biomes.encoded()); assertEquals(List.of("example:林地"), biomes.entries());
            assertNull(prep.mappings(world, 3, 1));
            mapper.getIdForBlockState(Blocks.GOLD_BLOCK.defaultBlockState());
            assertNull(prep.mappings(world, 3, 1).payload()); assertEquals(0, manager.tryRunAJob());
            assertEquals(4, prep.mappings(world, 3, 1).blockEnd());
            assertEquals(3, blocks.entries().size(), "new ids must not mutate already published pages");
            assertEquals(3, prep.stats().mappingPagesPrepared());
        } finally { prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void serializationScratchIsNeverBorrowedByPreparedSnapshots() {
        var world = world(); var manager = new ServiceManager(n -> {}); var prep = new LodServerPreparation(manager);
        try {
            for (int x = 1; x <= 2; x++) {
                var section = WorldSection._createRawUntrackedUnsafeSection(0, x, 0, 0);
                section.setUniform(Mapper.composeMappingId((byte) 15, x, 0)); world.storage.saveSection(section);
            }
            prep.section(world, key(1)); assertEquals(0, manager.tryRunAJob()); var first = prep.section(world, key(1));
            byte[] saved = first.data().clone();
            prep.section(world, key(2)); assertEquals(0, manager.tryRunAJob());
            assertArrayEquals(saved, first.data());
            var bytes = ByteBuffer.wrap(first.data()).order(ByteOrder.nativeOrder());
            assertEquals(key(1).section(), bytes.getLong());
            assertEquals(1, Mapper.getBlockId(bytes.getLong(16 + WorldSection.SECTION_VOLUME * 2)));
        } finally { prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void shutdownWaitsForRunningLoadAndReleasesQueuedWorldReferences() throws Exception {
        var world = world(); var manager = new ServiceManager(n -> {}); var loader = new Loader();
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        loader.duringLoad = () -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        };
        var prep = new LodServerPreparation(manager, loader, 2L << 20, 4, 8);
        var executor = Executors.newFixedThreadPool(2);
        try {
            prep.section(world, key(1)); prep.section(world, key(2));
            var running = executor.submit(manager::tryRunAJob);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var stopping = executor.submit(prep::close);
            assertThrows(TimeoutException.class, () -> stopping.get(100, TimeUnit.MILLISECONDS));
            assertTrue(world.isWorldUsed());
            finish.countDown(); assertEquals(0, running.get(5, TimeUnit.SECONDS)); stopping.get(5, TimeUnit.SECONDS);
            assertFalse(world.isWorldUsed()); assertEquals(0, prep.stats().inFlight()); assertEquals(0, prep.stats().bytes());
            assertNull(prep.section(world, key(1))); assertEquals(1, manager.tryRunAJob());
        } finally { finish.countDown(); executor.shutdownNow(); prep.close(); manager.shutdown(); world.free(); }
    }
}
