package me.cortex.voxy.client.core.rendering.building;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.PriorityBlockingQueue;

public class RenderGenerationService {
    // SeedGen injects into this actual task class and updatePriority's Math.min call.
    static final class BuildTask {
        final long position;
        int priority;
        long order;
        MeshTaskQueue.State state = MeshTaskQueue.State.RUNNABLE;
        boolean changed;
        long waitStart;
        final LongOpenHashSet dependencies = new LongOpenHashSet();
        BuildTask(long position) { this.position = position; }
        void updatePriority() {
            int level = WorldEngine.MAX_LOD_LAYER - WorldEngine.getLevel(this.position);
            this.priority = Math.min(level, 3);
        }
    }
    public static final AtomicInteger MESH_FAILED_COUNTER = new AtomicInteger();
    private final WorldEngine world;
    interface Models {
        boolean hasBlock(int block);
        boolean ready(long dependency);
        void request(int block);
        void addListener(LongConsumer listener);
        void removeListener(LongConsumer listener);
    }
    private final Models models;
    private volatile Consumer<BuiltSection> resultConsumer;
    private final Service service;
    private final MeshTaskQueue tasks;
    private final PriorityBlockingQueue<BuildTask> taskQueue = MeshTaskQueue.createRunnableQueue();
    private final ThreadLocal<Function<WorldSection, BuiltSection>> alternateBuilder = new ThreadLocal<>();
    private final LongConsumer modelReady;

    public RenderGenerationService(WorldEngine world, ModelBakerySubsystem modelBakery, ServiceManager sm, boolean emitMeshlets) {
        this.world = world;
        this.models = new Models() {
            public boolean hasBlock(int block) { return modelBakery.factory.hasModelForBlockId(block); }
            public boolean ready(long dependency) { return modelBakery.factory.isDependencyReady(dependency); }
            public void request(int block) { modelBakery.requestBlockBake(block); }
            public void addListener(LongConsumer listener) { modelBakery.factory.addModelReadyListener(listener); }
            public void removeListener(LongConsumer listener) { modelBakery.factory.removeModelReadyListener(listener); }
        };
        this.service = sm.createService(() -> {
            var factory = new RenderDataFactory(world, modelBakery.factory, emitMeshlets);
            var missing = new IntOpenHashSet(128);
            return new Pair<>(() -> this.processJob(factory, missing), factory::free);
        }, 4, "Section mesh generation service");
        this.tasks = new MeshTaskQueue(this.service::execute, this.models::ready, this.taskQueue);
        this.modelReady = this.tasks::dependencyReady;
        this.models.addListener(this.modelReady);
    }

    RenderGenerationService(WorldEngine world, Models models, ServiceManager sm,
                            Supplier<Pair<Function<WorldSection, BuiltSection>, Runnable>> builders) {
        this.world = world;
        this.models = models;
        this.service = sm.createService(() -> {
            var builder = builders.get();
            var missing = new IntOpenHashSet(128);
            return new Pair<>(() -> {
                this.alternateBuilder.set(builder.left());
                try { this.processJob(null, missing); }
                finally { this.alternateBuilder.remove(); }
            }, builder.right());
        }, 4, "Section mesh generation service");
        this.tasks = new MeshTaskQueue(this.service::execute, this.models::ready, this.taskQueue);
        this.modelReady = this.tasks::dependencyReady;
        this.models.addListener(this.modelReady);
    }

    public void setResultConsumer(Consumer<BuiltSection> consumer) { this.resultConsumer = consumer; }

    private void collectModel(IntOpenHashSet missing, long state) {
        int block = Mapper.getBlockId(state);
        if (block != 0 && !this.models.hasBlock(block)) missing.add(block);
    }

    private void collectModels(IntOpenHashSet missing, WorldSection section, IdNotYetComputedException failure) {
        if (failure.isIdBlockId) missing.add(failure.id);
        if (failure.substitutedSection != null) {
            for (long state : failure.substitutedSection) collectModel(missing, state);
        } else {
            long[] raw = section._rawOrNull();
            if (raw == null) collectModel(missing, section.getUniformValue());
            else for (long state : raw) collectModel(missing, state);
        }
        if (failure.auxData != null) {
            for (int face = 0; face < 6; face++) {
                if ((failure.auxBitMsk & (1 << face)) == 0) continue;
                for (int i = 0; i < 1024; i++) collectModel(missing, failure.auxData[face * 1024 + i]);
            }
        }
    }

    // Keep the factory signature, queue poll and both Consumer.accept sites for SeedGen's
    // freshness hooks; the task state transition shares the queue's monitor.
    private void processJob(RenderDataFactory factory, IntOpenHashSet missing) {
        BuildTask task;
        synchronized (this.tasks) { task = this.tasks.claim(this.taskQueue.poll()); }
        if (task == null) return;
        LongOpenHashSet dependencies = null;
        try {
            var section = this.world.acquireIfExists(task.position);
            BuiltSection mesh;
            if (section == null) {
                var consumer = this.resultConsumer;
                if (consumer != null) consumer.accept(BuiltSection.empty(task.position));
                return;
            } else {
                try {
                    mesh = factory != null ? factory.generateMesh(section) : this.alternateBuilder.get().apply(section);
                } catch (IdNotYetComputedException failure) {
                    MESH_FAILED_COUNTER.incrementAndGet();
                    missing.clear();
                    collectModels(missing, section, failure);
                    var pending = new LongOpenHashSet();
                    for (int block : missing) pending.add(ModelFactory.blockDependency(block));
                    if (!failure.isIdBlockId) pending.add(ModelFactory.fluidDependency(failure.id));
                    for (int block : missing) this.models.request(block);
                    dependencies = pending;
                    return;
                } finally {
                    // No waiting task owns a section; resumption always acquires current data.
                    section.release();
                }
            }
            var consumer = this.resultConsumer;
            if (consumer != null) consumer.accept(mesh);
            else mesh.free();
        } finally {
            if (dependencies != null) this.tasks.await(task, dependencies);
            else this.tasks.complete(task);
        }
    }

    public void enqueueTask(long pos) { this.tasks.enqueue(pos); }

    public void shutdown() {
        this.tasks.close();
        this.models.removeListener(this.modelReady);
        this.service.shutdown();
    }

    private long lastChangedTime;
    public void addDebugData(List<String> debug) {
        if (System.currentTimeMillis() - this.lastChangedTime > 100) {
            MESH_FAILED_COUNTER.set(0);
            this.lastChangedTime = System.currentTimeMillis();
        }
        debug.add("RSSQ/WAIT/ALL/TFC: " + getRunnableTaskCount() + "/" + getWaitingTaskCount()
                + "/" + getTaskCount() + "/" + MESH_FAILED_COUNTER.get());
    }

    public int getTaskCount() { return this.tasks.taskCount(); }
    public int getRunnableTaskCount() { return this.tasks.runnableCount(); }
    public int getWaitingTaskCount() { return this.tasks.waitingCount(); }
}
