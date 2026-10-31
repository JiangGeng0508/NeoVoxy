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
}
