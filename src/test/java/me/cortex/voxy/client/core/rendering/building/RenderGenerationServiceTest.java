package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

class RenderGenerationServiceTest {
    static final class Models implements RenderGenerationService.Models {
        final LongOpenHashSet ready = new LongOpenHashSet();
        final List<Integer> requested = new ArrayList<>();
        LongConsumer listener;
        boolean immediate;
        public boolean hasBlock(int block) { return ready.contains(ModelFactory.blockDependency(block)); }
        public boolean ready(long dependency) { return ready.contains(dependency); }
        public void request(int block) {
            requested.add(block);
            if (immediate) complete(ModelFactory.blockDependency(block));
        }
        public void addListener(LongConsumer listener) { this.listener = listener; }
        public void removeListener(LongConsumer listener) { assertSame(this.listener, listener); this.listener = null; }
        void complete(long key) { ready.add(key); if (listener != null) listener.accept(key); }
    }

    @Test void waitingReleasesSectionAndResumptionReadsLatestVoxels() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {});
        var models = new Models();
        var section = world.acquire(0, 0, 0, 0);
        section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var reads = new ArrayList<Integer>();
        var renderer = new RenderGenerationService(world, models, manager, () -> new Pair<>(s -> {
            int block = Mapper.getBlockId(s.get(0)); reads.add(block);
            if (!models.hasBlock(block)) throw new IdNotYetComputedException(block, true);
            return BuiltSection.empty(s.key);
        }, () -> {}));
        try {
            renderer.enqueueTask(section.key);
            assertEquals(0, manager.tryRunAJob());
            assertEquals(1, section.getRefCount(), "waiting must release the acquired mesh reference");
            assertEquals(0, renderer.getRunnableTaskCount()); assertEquals(1, renderer.getWaitingTaskCount());
            for (int i = 0; i < 10; i++) { renderer.enqueueTask(section.key); assertEquals(1, manager.tryRunAJob()); }
            section.setUniform(Mapper.composeMappingId((byte) 15, 2, 0));
            models.ready.add(ModelFactory.blockDependency(2));
            models.complete(ModelFactory.blockDependency(1));
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(1, 2), reads);
            assertEquals(0, renderer.getTaskCount()); assertEquals(1, section.getRefCount());
        } finally { renderer.shutdown(); manager.shutdown(); section.release(); world.free(); }
    }

    @Test void immediateBakeCompletionDoesNotLoseWakeupAndFallbackCompletesTheTask() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {}); var models = new Models(); models.immediate = true;
        var section = world.acquire(0, 0, 0, 0); section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var count = new AtomicInteger();
        var renderer = new RenderGenerationService(world, models, manager, () -> new Pair<>(s -> {
            count.incrementAndGet();
            if (!models.hasBlock(1)) throw new IdNotYetComputedException(1, true);
            return BuiltSection.empty(s.key); // Published fallback model 0 also resolves the dependency.
        }, () -> {}));
        try {
            renderer.enqueueTask(section.key);
            assertEquals(0, manager.tryRunAJob()); assertEquals(1, renderer.getRunnableTaskCount());
            assertEquals(0, manager.tryRunAJob()); assertEquals(0, renderer.getTaskCount()); assertEquals(2, count.get());
        } finally { renderer.shutdown(); manager.shutdown(); section.release(); world.free(); }
    }

    @Test void shutdownDetachesWaitingListenerReleasesContextsAndRejectsLateWakeups() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {}); var models = new Models();
        var section = world.acquire(0, 0, 0, 0); section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var cleaned = new AtomicInteger();
        var renderer = new RenderGenerationService(world, models, manager,
                () -> new Pair<>(s -> { throw new IdNotYetComputedException(1, true); }, cleaned::incrementAndGet));
        renderer.enqueueTask(section.key); assertEquals(0, manager.tryRunAJob());
        renderer.shutdown(); models.complete(ModelFactory.blockDependency(1)); renderer.enqueueTask(section.key);
        assertEquals(0, renderer.getTaskCount()); assertEquals(1, cleaned.get()); assertNull(models.listener);
        assertEquals(1, section.getRefCount());
        manager.shutdown(); section.release(); world.free();
    }

    @Test void unexpectedBuildFailureReleasesSectionAndRemovesRunningTask() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {}); var models = new Models();
        var section = world.acquire(0, 0, 0, 0);
        var renderer = new RenderGenerationService(world, models, manager,
                () -> new Pair<>(s -> { throw new IllegalStateException("injected mesh failure"); }, () -> {}));
        try {
            renderer.enqueueTask(section.key); assertEquals(0, manager.tryRunAJob());
            assertEquals(0, renderer.getTaskCount()); assertEquals(1, section.getRefCount());
        } finally { renderer.shutdown(); manager.shutdown(); section.release(); world.free(); }
    }

    @Test void fluidWaitUsesClientModelNamespaceAndDoesNotRequestItAsABlock() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {}); var models = new Models();
        models.ready.add(ModelFactory.blockDependency(1));
        var section = world.acquire(0, 0, 0, 0); section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var renderer = new RenderGenerationService(world, models, manager, () -> new Pair<>(s -> {
            if (!models.ready(ModelFactory.fluidDependency(3))) throw new IdNotYetComputedException(3, false);
            return BuiltSection.empty(s.key);
        }, () -> {}));
        try {
            renderer.enqueueTask(section.key); assertEquals(0, manager.tryRunAJob());
            assertTrue(models.requested.isEmpty()); assertEquals(1, renderer.getWaitingTaskCount());
            models.complete(ModelFactory.blockDependency(3));
            assertEquals(1, manager.tryRunAJob()); assertEquals(1, renderer.getWaitingTaskCount());
            models.complete(ModelFactory.fluidDependency(3));
            assertEquals(0, manager.tryRunAJob()); assertEquals(0, renderer.getTaskCount());
        } finally { renderer.shutdown(); manager.shutdown(); section.release(); world.free(); }
    }

    @Test void fullResultBudgetKeepsOnlyTaskKeyAndResumesFromLatestData() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {}); var models = new Models();
        var section = world.acquire(0, 0, 0, 0);
        section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var budget = MeshResultBudgetTest.singleSlot();
        var outputs = new ArrayList<BuiltSection>();
        var reads = new ArrayList<Integer>();
        var renderer = new RenderGenerationService(world, models, manager, () -> new Pair<>(s -> {
            reads.add(Mapper.getBlockId(s.get(0)));
            return MeshResultBudgetTest.mesh(s.key);
        }, () -> {}), budget);
        renderer.setResultConsumer(outputs::add);
        var otherWork = new AtomicInteger();
        var other = manager.createServiceNoCleanup(() -> otherWork::incrementAndGet, 1, "other work");
        try {
            renderer.enqueueTask(section.key); assertEquals(0, manager.tryRunAJob());
            renderer.enqueueTask(section.key);
            for (int i = 0; i < 10; i++) {
                renderer.enqueueTask(section.key);
                assertEquals(3, manager.tryRunAJob());
            }
            assertEquals(List.of(1), reads); assertEquals(1, renderer.getTaskCount());
            assertEquals(1, section.getRefCount());
            other.execute(); assertEquals(0, manager.tryRunAJob()); assertEquals(1, otherWork.get());
            section.setUniform(Mapper.composeMappingId((byte) 15, 2, 0));
            outputs.removeFirst().free();
            assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of(1, 2), reads); assertEquals(0, renderer.getTaskCount());
        } finally {
            renderer.shutdown(); other.shutdown(); manager.shutdown();
            outputs.forEach(BuiltSection::free); section.release(); world.free();
        }
        assertEquals(0, budget.stats().entries());
    }

    @Test void shutdownWithCapacityBlockedKeysDoesNotWaitForConsumer() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {});
        var budget = MeshResultBudgetTest.singleSlot();
        var outputs = new ArrayList<BuiltSection>();
        var renderer = new RenderGenerationService(world, new Models(), manager,
                () -> new Pair<>(s -> BuiltSection.empty(s.key), () -> {}), budget);
        renderer.setResultConsumer(outputs::add);
        renderer.enqueueTask(WorldEngine.getWorldSectionId(0, 0, 0, 0));
        assertEquals(0, manager.tryRunAJob());
        renderer.enqueueTask(WorldEngine.getWorldSectionId(0, 1, 0, 0));
        assertEquals(3, manager.tryRunAJob());
        renderer.shutdown(); manager.shutdown();
        assertEquals(0, renderer.getTaskCount());
        assertEquals(1, budget.stats().entries(), "consumer still owns the already published empty result");
        outputs.forEach(BuiltSection::free);
        assertEquals(0, budget.stats().entries());
        world.free();
    }

    @Test void modelWaitAndBuildFailureReturnTheirEntireReservation() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {}); var models = new Models();
        var section = world.acquire(0, 0, 0, 0); section.setUniform(Mapper.composeMappingId((byte) 15, 1, 0));
        var budget = MeshResultBudgetTest.singleSlot();
        var renderer = new RenderGenerationService(world, models, manager, () -> new Pair<>(s -> {
            if (!models.hasBlock(1)) throw new IdNotYetComputedException(1, true);
            throw new IllegalStateException("injected build failure after model wakeup");
        }, () -> {}), budget);
        try {
            renderer.enqueueTask(section.key); assertEquals(0, manager.tryRunAJob());
            assertEquals(1, renderer.getWaitingTaskCount()); assertEquals(0, budget.stats().entries());
            models.complete(ModelFactory.blockDependency(1)); assertEquals(0, manager.tryRunAJob());
            assertEquals(0, budget.stats().entries()); assertEquals(0, budget.stats().nativeBytes());
            assertEquals(1, section.getRefCount());
        } finally { renderer.shutdown(); manager.shutdown(); section.release(); world.free(); }
    }

    @Test void shutdownWaitsForRunningBuildAndLeavesPublishedOwnershipWithConsumer() throws Exception {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var manager = new ServiceManager(n -> {});
        var section = world.acquire(0, 0, 0, 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var budget = MeshResultBudgetTest.singleSlot();
        var output = new java.util.concurrent.atomic.AtomicReference<BuiltSection>();
        var renderer = new RenderGenerationService(world, new Models(), manager, () -> new Pair<>(s -> {
            entered.countDown();
            try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return MeshResultBudgetTest.mesh(s.key);
        }, () -> {}), budget);
        renderer.setResultConsumer(output::set);
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            renderer.enqueueTask(section.key);
            var worker = threads.submit(manager::tryRunAJob);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, budget.stats().building()); assertEquals(2, section.getRefCount());
            renderer.enqueueTask(WorldEngine.getWorldSectionId(0, 1, 0, 0));
            assertEquals(3, manager.tryRunAJob());
            var closing = threads.submit(renderer::shutdown);
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (renderer.getTaskCount() != 0 && System.nanoTime() < deadline) Thread.yield();
                assertEquals(0, renderer.getTaskCount());
                assertFalse(closing.isDone());
            } finally { release.countDown(); }
            assertEquals(0, worker.get(5, java.util.concurrent.TimeUnit.SECONDS));
            closing.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, section.getRefCount());
            assertEquals(1, budget.stats().entries()); assertEquals(0, budget.stats().building());
            assertNotNull(output.get());
        } finally {
            release.countDown();
            if (output.get() != null) output.get().free();
            manager.shutdown(); section.release(); world.free();
        }
        assertEquals(0, budget.stats().entries()); assertEquals(0, budget.stats().nativeBytes());
    }
}
