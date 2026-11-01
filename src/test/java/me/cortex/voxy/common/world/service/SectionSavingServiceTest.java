package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SectionSavingServiceTest {
    @Test void trackedSectionEntersSecondaryCacheOnlyAfterRetryCommits() {
        var manager = new ServiceManager(n -> {});
        var storage = new Storage(); storage.failCommit = true;
        var world = new WorldEngine(storage);
        var saver = new SectionSavingService(manager, storage.clock::get);
        world.setSaveCallback(saver::enqueueSave);
        try {
            var section = world.acquire(0, 0, 0, 0);
            synchronized (section) { java.util.Arrays.fill(section.materialize(), Mapper.airWithLight(15)); }
            world.markDirty(section); section.release();
            assertEquals(1, world.getActiveSectionCount()); assertEquals(0, world.getSecondaryCacheStats().entries());
            assertEquals(0, manager.tryRunAJob());
            assertEquals(1, world.getActiveSectionCount()); assertEquals(0, world.getSecondaryCacheStats().entries());
            assertFalse(section.isFreed());
            storage.failCommit = false;
            assertEquals(0, manager.tryRunAJob());
            assertEquals(0, world.getActiveSectionCount()); assertEquals(1, world.getSecondaryCacheStats().entries());
            assertEquals(WorldSection.SECTION_VOLUME * 8L, world.getSecondaryCacheStats().voxelBytes());
            assertEquals(0, storage.loadSection(WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0)));
        } finally { storage.failCommit = false; saver.shutdown(); manager.shutdown(); world.free(); }
    }

    static class Storage extends SectionSerializationStorage {
        final List<Integer> batches = new ArrayList<>();
        long bytesPerSection;
        long nanosPerSection;
        final AtomicLong clock = new AtomicLong();
        boolean failCommit;
        Storage() { super(new MemoryStorageBackend()); }
        @Override public SectionSaveBatch createSaveBatch() {
            var delegate = super.createSaveBatch();
            return new SectionSaveBatch() {
                int sections;
                @Override public void add(WorldSection section) {
                    delegate.add(section);
                    sections++;
                    clock.addAndGet(nanosPerSection);
                }
                @Override public long dataSize() { return bytesPerSection == 0 ? delegate.dataSize() : bytesPerSection * sections; }
                @Override public void commit() {
                    if (failCommit) throw new IllegalStateException("injected commit failure");
                    delegate.commit();
                    batches.add(sections);
                    sections = 0;
                }
                @Override public void close() { delegate.close(); }
            };
        }
    }

    private static List<WorldSection> enqueue(SectionSavingService saver, WorldEngine engine, int count) {
        var sections = new ArrayList<WorldSection>();
        for (int x = 0; x < count; x++) {
            var section = WorldSection._createRawUntrackedUnsafeSection(0, x, 0, 0);
            section.acquire();
            section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
            section.markDirty();
            assertTrue(saver.enqueueSave(engine, section, true, false));
            assertEquals(2, section.getRefCount());
            sections.add(section);
        }
        return sections;
    }

    @Test void countLimitYieldsToOtherServicesAndShutdownDrainsTheRest() {
        var manager = new ServiceManager(n -> {});
        var storage = new Storage();
        var world = new WorldEngine(storage);
        var saver = new SectionSavingService(manager, storage.clock::get);
        var otherRan = new ArrayList<Integer>();
        var other = manager.createServiceNoCleanup(() -> () -> otherRan.add(storage.batches.size()), 4);
        var sections = enqueue(saver, world, 200);
        try {
            other.execute();
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(64), storage.batches);
            assertEquals(136, saver.getTaskCount());
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(64, 64), storage.batches);
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(2), otherRan, "saving must surrender the worker after two bounded invocations");
            saver.shutdown();
            assertEquals(List.of(64, 64, 64, 8), storage.batches);
            assertEquals(0, saver.getTaskCount());
            for (var section : sections) {
                assertEquals(1, section.getRefCount());
                assertFalse(section.shouldSave());
                assertEquals(0, storage.loadSection(WorldSection._createRawUntrackedUnsafeSection(0, section.x, 0, 0)));
            }
        } finally {
            other.shutdown(); manager.shutdown();
            sections.forEach(WorldSection::release); world.free();
        }
    }

    @Test void byteLimitStopsAtTheNextSectionBoundary() { verifyBound(2L << 20, 0, 2); }
    @Test void timeLimitStopsAtTheNextSectionBoundary() { verifyBound(0, 2_100_000, 2); }
    @Test void singleSlowSectionStillMakesProgressBeforeYielding() { verifyBound(0, 7_000_000, 1); }

    private static void verifyBound(long bytes, long nanos, int firstBatch) {
        var manager = new ServiceManager(n -> {});
        var storage = new Storage(); storage.bytesPerSection = bytes; storage.nanosPerSection = nanos;
        var world = new WorldEngine(storage);
        var saver = new SectionSavingService(manager, storage.clock::get);
        var sections = enqueue(saver, world, 5);
        try {
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(firstBatch), storage.batches);
            assertEquals(5 - firstBatch, saver.getTaskCount());
            saver.shutdown();
            assertEquals(5, storage.batches.stream().mapToInt(Integer::intValue).sum());
            assertEquals(1, manager.tryRunAJob());
        } finally { manager.shutdown(); sections.forEach(WorldSection::release); world.free(); }
    }

    @Test void dimensionSwitchEndsTheInvocationAndNeverMixesStorage() {
        var manager = new ServiceManager(n -> {});
        var a = new Storage(); var b = new Storage();
        var wa = new WorldEngine(a); var wb = new WorldEngine(b);
        var saver = new SectionSavingService(manager, a.clock::get);
        var sa = enqueue(saver, wa, 1); var sb = enqueue(saver, wb, 1);
        try {
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(1), a.batches); assertTrue(b.batches.isEmpty());
            assertEquals(1, saver.getTaskCount());
            saver.shutdown(); assertEquals(List.of(1), b.batches);
        } finally { manager.shutdown(); sa.forEach(WorldSection::release); sb.forEach(WorldSection::release); wa.free(); wb.free(); }
    }

    @Test void commitFailureReleasesSaverReferencesAndLeavesSectionsRetryable() {
        var manager = new ServiceManager(n -> {});
        var storage = new Storage(); storage.failCommit = true;
        var world = new WorldEngine(storage);
        var saver = new SectionSavingService(manager, storage.clock::get);
        var sections = enqueue(saver, world, 2);
        try {
            assertEquals(0, manager.tryRunAJob());
            for (var section : sections) { assertEquals(1, section.getRefCount()); assertTrue(section.shouldSave()); }
            storage.failCommit = false;
            for (var section : sections) assertTrue(saver.enqueueSave(world, section, true, false));
            saver.shutdown();
            for (var section : sections) assertFalse(section.shouldSave());
        } finally { manager.shutdown(); sections.forEach(WorldSection::release); world.free(); }
    }
}
