package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.voxelization.ILightingSupplier;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;

/** 将 Minecraft 区段快照排队转换为 Voxy 的体素区段。 */
public class VoxelIngestService {
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private final Service service;
    /** 队列元素持有一个 WorldEngine 引用，任务完成或丢弃时必须释放。 */
    record IngestSection(
            int cx, int cy, int cz,
            WorldEngine world,
            LevelChunk chunk,
            BlockEntity[] domumBlockEntities,
            LevelChunkSection section,
            DataLayer blockLight,
            DataLayer skyLight,
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.SectionSnapshot littleTiles,
            IngestBatch batch, int compatibilityMask) {
    }
    private final IngestTaskQueue<IngestSection> ingestQueue;
    private final Consumer<LevelChunkSection> beforeConvert;

    /**
     * Tracks completion of a group of enqueued ingest tasks. The caller declares how many tasks it
     * will enqueue via {@link #await(int)} BEFORE enqueueing them; every completed (or refused) task
     * must be reported exactly once via {@link #taskDone}. When the count reaches zero the callback
     * fires with {@link #succeeded()} reporting whether anything failed. The callback may run on any
     * thread.
     */
    public static final class IngestBatch {
        private final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.function.Consumer<IngestBatch> onDone;

        private IngestBatch(java.util.function.Consumer<IngestBatch> onDone) {
            this.onDone = onDone;
        }

        /** Declares the number of tasks this batch will track. Must be called before enqueueing. */
        public void await(int tasks) {
            this.remaining.addAndGet(tasks);
        }

        /** Reports one tracked task as finished; a task refused before running reports false. */
        public void taskDone(boolean success) {
            if (!success) {
                this.failed.set(true);
            }
            if (this.remaining.decrementAndGet() == 0) {
                this.onDone.accept(this);
            }
        }

        public boolean succeeded() {
            return !this.failed.get();
        }
    }

    public static IngestBatch createBatch(java.util.function.Consumer<IngestBatch> onDone) {
        return new IngestBatch(onDone);
    }

    public VoxelIngestService(ServiceManager pool) {
        this(pool, section -> {}, IngestTaskQueue.HEAP_TARGET, IngestTaskQueue.TASK_TARGET);
    }

    VoxelIngestService(ServiceManager pool, Consumer<LevelChunkSection> beforeConvert, long byteTarget, int taskTarget) {
        this.beforeConvert = beforeConvert;
        this.service = pool.createServiceNoCleanup(()->this::processJob, 4, "Ingest service");
        this.ingestQueue = new IngestTaskQueue<>(this.service::execute, byteTarget, taskTarget, VoxelIngestService::mergePending);
    }

    // ---- 后台任务 ------------------------------------------------------

    private void processJob() {
        var queued = this.ingestQueue.take();
        if (queued == null) return;
        var task = queued.value();
        boolean ok = false;

        var section = task.section;
        long tIngest = me.cortex.voxy.commonImpl.VoxyProfile.begin();
        try {
            this.beforeConvert.accept(section);
            //Inside the try: the queue holds a world ref per task and the finally below releases it, so
            //anything that can throw has to be covered or the world can never be closed again
            DomumOrnamentumCompat.beginSection(
                    task.world.getMapper(), task.world.storage, task.domumBlockEntities,
                    task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.beginSection(task.world.getMapper(), task.world.storage, task.chunk, task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.beginSection(task.world.getMapper(), task.world.storage, task.chunk, task.section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.beginSection(
                    task.world.storage, task.littleTiles, task.section, task.cx, task.cy, task.cz);
            //Read off the section rather than the chunk's block entities: sections streamed by VSS arrive
            //with no chunk at all, and a beacon is a block whether or not its block entity is here.
            long tBeacon = me.cortex.voxy.commonImpl.VoxyProfile.begin();
            me.cortex.voxy.common.world.other.BeaconScanner.scan(
                    task.world.getBeaconIndex(), section, task.cx, task.cy, task.cz);
            me.cortex.voxy.commonImpl.VoxyProfile.end("ingest/beaconScan", tBeacon);
            var vs = SECTION_CACHE.get().setPosition(task.cx, task.cy, task.cz);

            if (section.hasOnlyAir() && task.blockLight==null && task.skyLight==null) {//If the chunk section has lighting data, propagate it
                WorldUpdater.insertUpdate(task.world, vs.uniformAir(me.cortex.voxy.common.world.other.Mapper.airWithLight(0x0F)));
            } else {
                VoxelizedSection csec = WorldConversionFactory.convert(
                        vs,
                        task.world.getMapper(),
                        section.getStates(),
                        section.getBiomes(),
                        getLightingSupplier(task)
                );
                WorldVoxilizedSectionMipper.mipSection(csec, task.world.getMapper());
                WorldUpdater.insertUpdate(task.world, csec);
            }
            ok = true;
        } finally {
            try {
                DomumOrnamentumCompat.endSection();
                me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.endSection();
                me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.endSection();
                me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.endSection();
            } finally {
                try {
                    finish(task, ok);
                    me.cortex.voxy.commonImpl.VoxyProfile.end("ingest/section", tIngest);
                } finally {
                    // Publish the next version only after the previous voxel/mip writes finished.
                    this.ingestQueue.complete(queued);
                }
            }
        }
    }

    private static void finish(IngestSection task, boolean success) {
        try { task.world.releaseRef(); }
        finally {
            if (task.batch != null) {
                try { task.batch.taskDone(success); }
                catch (Exception e) { Logger.error("Ingest completion callback failed", e); }
            }
        }
    }

    // ---- 光照快照 ------------------------------------------------------

    @NotNull
    private static ILightingSupplier getLightingSupplier(IngestSection task) {
        ILightingSupplier supplier = (x,y,z) -> (byte) 0;
        var sla = task.skyLight;
        var bla = task.blockLight;
        boolean sl = sla != null && !sla.isEmpty();
        boolean bl = bla != null && !bla.isEmpty();
        if (sl || bl) {
            if (sl && bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            } else if (bl) {
                supplier = (x,y,z)-> {
                    int block = Math.min(15,bla.get(x, y, z));
                    int sky = 0;
                    return (byte) (sky|(block<<4));
                };
            } else {
                supplier = (x,y,z)-> {
                    int block = 0;
                    int sky = Math.min(15,sla.get(x, y, z));
                    return (byte) (sky|(block<<4));
                };
            }
        }
        return supplier;
    }

    private static boolean shouldIngestSection(LevelChunkSection section, int cx, int cy, int cz) {
        return true;
    }

    // ---- 区段入队 ------------------------------------------------------

    public boolean enqueueIngest(WorldEngine engine, LevelChunk chunk) {
        if (!this.ingestQueue.isOpen()) {
            return false;
        }
        if (!engine.isLive()) {
            throw new IllegalStateException("Tried inserting chunk into WorldEngine that was not alive");
        }
        engine.acquireRef();
        try { return this.enqueueChunkSnapshot(engine, chunk); }
        finally { engine.releaseRef(); }
    }

    private boolean enqueueChunkSnapshot(WorldEngine engine, LevelChunk chunk) {
        // Snapshot and group once on the caller thread. Each section job receives only its Domum
        // entities, avoiding repeated traversal of the live block-entity map on ingest workers.
        var domumBlockEntities = DomumOrnamentumCompat.captureBlockEntitiesBySection(chunk);
        var littleTiles = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.capture(chunk);

        var lightingProvider = chunk.getLevel().getLightEngine();
        boolean gotLighting = false;

        int i = chunk.getMinSection() - 1;
        boolean allEmpty = true;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            allEmpty&=section.hasOnlyAir();
            var pos = SectionPos.of(chunk.getPos(), i);
            if (lightingProvider.getDebugSectionType(LightLayer.SKY, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA && lightingProvider.getDebugSectionType(LightLayer.BLOCK, pos) != LayerLightSectionStorage.SectionType.LIGHT_AND_DATA)
                continue;
            gotLighting = true;
        }

        if (!gotLighting && !allEmpty) return false;

        var blp = lightingProvider.getLayerListener(LightLayer.BLOCK);
        var slp = lightingProvider.getLayerListener(LightLayer.SKY);


        i = chunk.getMinSection() - 1;
        for (var section : chunk.getSections()) {
            i++;
            if (section == null || !shouldIngestSection(section, chunk.getPos().x, i, chunk.getPos().z)) continue;
            var pos = SectionPos.of(chunk.getPos(), i);

            var bl = gotLighting ? blp.getDataLayerData(pos) : null;
            var sl = gotLighting ? slp.getDataLayerData(pos) : null;
            if (gotLighting && sl == null) {
                //Sections above the sky-light storage range have no DataLayer but are implicitly fully lit.
                //Null-data sections are uniform, so probe one block for the value; dark sections and
                //skylight-less dimensions probe 0 and stay unchanged.
                int uniform = slp.getLightValue(pos.origin());
                if (uniform > 0) {
                    sl = new DataLayer(uniform);
                }
            }

            if (!this.enqueueSnapshot(engine, chunk, section, chunk.getPos().x, i, chunk.getPos().z, bl, sl,
                    domumBlockEntities.forSection(i), littleTiles == null ? null : littleTiles.section(i), null)) return false;
        }
        // Preserve the existing no-light return contract even when an all-air column was cleared.
        return gotLighting;
    }

    public int getTaskCount() {
        return this.ingestQueue.stats().tasks();
    }

    public record Stats(int queued, int running, int keys, long arrayBytes, long compatibilityBytes,
                        long peakBytes, long coalesced, long refused, long overTarget, long byteTarget, int taskTarget) {
        public double pressure() {
            return Math.max((double) (arrayBytes + compatibilityBytes) / byteTarget, (double) (queued + running) / taskTarget);
        }
    }

    public Stats getStats() {
        var s = this.ingestQueue.stats();
        return new Stats(s.queued(), s.running(), s.keys(), s.arrayBytes(), s.compatibilityBytes(),
                s.peakBytes(), s.coalesced(), s.refused(), s.overTarget(), s.byteTarget(), s.taskTarget());
    }

    public void addDebug(List<String> debug) {
        var s = getStats();
        debug.add("Ingest queued/running/keys: " + s.queued() + "/" + s.running() + "/" + s.keys());
        debug.add("Ingest heap arrays/compat estimate/target KiB: " + (s.arrayBytes() >> 10) + "/"
                + (s.compatibilityBytes() >> 10) + "/" + (s.byteTarget() >> 10));
        debug.add("Ingest coalesced/retry refused/over target: " + s.coalesced() + "/" + s.refused() + "/" + s.overTarget());
    }

    /** 等待摄取积压清空；超时或中断时返回 false。 */
    public boolean blockTillEmpty(long timeoutMs) {
        long start = System.nanoTime();
        long timeout = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        while (this.getTaskCount() != 0) {
            if (System.nanoTime() - start >= timeout) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 停止服务并释放队列中尚未执行任务持有的世界引用。 */
    public void shutdown() {
        // Closing admission and draining pending ownership precedes stopping the service. No caller
        // can append after the drain; running jobs may finish but cannot schedule a new version.
        for (var task : this.ingestQueue.close()) finish(task.value(), false);
        this.service.shutdown();
    }

    // ---- 公共入口 ------------------------------------------------------

    /** 将已加载区块送入对应世界的摄取队列。 */
    public static boolean tryIngestChunk(WorldIdentifier worldId, LevelChunk chunk) {
        if (worldId == null) return false;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return false;
        if (!instance.isIngestEnabled(worldId)) return false;
        var engine = instance.getOrCreate(worldId);
        if (engine == null) return false;
        return instance.getIngestService().enqueueIngest(engine, chunk);
    }

    /** 根据区块所在维度自动选择目标世界。 */
    public static boolean tryAutoIngestChunk(LevelChunk chunk) {
        return tryIngestChunk(WorldIdentifier.of(chunk.getLevel()), chunk);
    }

    /** 供网络/兼容层提交单个区段，调用方不直接接触队列引用计数。 */
    private boolean rawIngest0(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return this.rawIngest0(engine, chunk, section, x, y, z, bl, sl, null);
    }

    boolean rawIngest0(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, IngestBatch batch) {
        if (!this.ingestQueue.isOpen()) return false;
        boolean capturing = false;
        try {
            engine.acquireRef();
            capturing = true;
            var domumBlockEntities = DomumOrnamentumCompat.captureBlockEntities(chunk, section);
            var littleTiles = me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.capture(chunk);
            return this.enqueueSnapshot(engine, chunk, section, x, y, z, bl, sl, domumBlockEntities,
                    littleTiles == null ? null : littleTiles.section(y), batch);
        } catch (Exception e) {
            Logger.error("Could not capture/enqueue ingest section", e);
            return false;
        } finally {
            if (capturing) engine.releaseRef();
        }
    }

    private boolean enqueueSnapshot(WorldEngine engine, LevelChunk chunk, LevelChunkSection section,
                                    int x, int y, int z, DataLayer bl, DataLayer sl, BlockEntity[] domum,
                                    me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.SectionSnapshot littleTiles,
                                    IngestBatch batch) {
        engine.acquireRef();
        boolean accepted = false;
        try {
            var snapshot = IngestSnapshot.capture(section, bl, sl);
            // Plain terrain only needs the private palettes/lights. Keep a live chunk solely when
            // an existing compatibility adapter still needs its block entities during conversion.
            int compatibility = 0;
            if (snapshot.section().maybeHas(me.cortex.voxy.commonImpl.compat.CreateCopycatCompat::isCopycatState)
                    || snapshot.section().maybeHas(me.cortex.voxy.commonImpl.compat.FramedBlocksCompat::isFramedState)) compatibility |= 1;
            if (snapshot.section().maybeHas(DomumOrnamentumCompat::isDomumState)) compatibility |= 2;
            if (me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.ENABLED
                    && snapshot.section().maybeHas(me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat::isLittleTilesState)) compatibility |= 4;
            // Preserve the adapters' existing cleanup path even if the palette only remembers a
            // removed holder. This is separate from permission to inherit a missing old source.
            boolean needsAdapterChunk = (compatibility & 1) != 0;
            if (compatibility != 0) compatibility = IngestSnapshot.activeCompatibilityMask(snapshot.section(), state -> {
                int kind = 0;
                if (me.cortex.voxy.commonImpl.compat.CreateCopycatCompat.isCopycatState(state)
                        || me.cortex.voxy.commonImpl.compat.FramedBlocksCompat.isFramedState(state)) kind |= 1;
                if (DomumOrnamentumCompat.isDomumState(state)) kind |= 2;
                if (me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.ENABLED
                        && me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat.isLittleTilesState(state)) kind |= 4;
                return kind;
            });
            var retainedChunk = needsAdapterChunk ? chunk : null;
            var task = new IngestSection(x, y, z, engine, retainedChunk, domum, snapshot.section(),
                    snapshot.blockLight(), snapshot.skyLight(), littleTiles, batch, compatibility);
            var result = this.ingestQueue.offer(new IngestTaskQueue.Task<>(new IngestTaskQueue.Key(engine, x, y, z),
                    task, snapshot.arrayBytes(), IngestSnapshot.compatibilityEstimate(domum.length, littleTiles)), batch != null);
            accepted = result.accepted();
            if (result.replaced() != null) finish(result.replaced().value(), false);
            // false transfers neither world ownership nor batch completion responsibility.
            return accepted;
        } finally {
            if (!accepted) engine.releaseRef();
        }
    }

    static IngestTaskQueue.Task<IngestSection> mergePending(IngestTaskQueue.Task<IngestSection> previous,
                                                           IngestTaskQueue.Task<IngestSection> next) {
        var old = previous.value(); var value = next.value();
        // A chunkless update normally restores compatibility data persisted by earlier ingestion.
        // If that earlier task is still pending, carry its missing side data instead of losing it
        // during coalescing. New explicit data wins, and removed holder states never inherit it.
        var chunk = value.chunk == null && (value.compatibilityMask & 1) != 0 ? old.chunk : value.chunk;
        var domum = value.domumBlockEntities.length == 0 && (value.compatibilityMask & 2) != 0
                ? old.domumBlockEntities : value.domumBlockEntities;
        var littleTiles = value.littleTiles == null && (value.compatibilityMask & 4) != 0 ? old.littleTiles : value.littleTiles;
        var merged = new IngestSection(value.cx, value.cy, value.cz, value.world, chunk, domum, value.section,
                value.blockLight, value.skyLight, littleTiles, value.batch, value.compatibilityMask);
        return new IngestTaskQueue.Task<>(next.key(), merged, next.arrayBytes(),
                IngestSnapshot.compatibilityEstimate(domum.length, littleTiles));
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        return rawIngest(id, recoverChunk(id, x, z), section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, IngestBatch batch) {
        return rawIngest(id, recoverChunk(id, x, z), section, x, y, z, bl, sl, batch);
    }

    private static LevelChunk recoverChunk(WorldIdentifier id, int chunkX, int chunkZ) {
        if (id == null) {
            return null;
        }
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return null;
        }
        try {
            //Named only here, so this class never resolves a client type on a dedicated server
            return me.cortex.voxy.client.ClientChunkRecovery.find(id, chunkX, chunkZ);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, chunk, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldIdentifier id, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, IngestBatch batch) {
        if (id == null) return false;
        var engine = id.getOrCreateEngine();
        if (engine == null) return false;
        return rawIngest(engine, chunk, section, x, y, z, bl, sl, batch);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (chunk == null) {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedChunkless.increment();
        } else {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedWithChunk.increment();
        }
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;
        return engine.instanceIn.getIngestService().rawIngest0(engine, chunk, section, x, y, z, bl, sl);
    }

    public static boolean rawIngest(WorldEngine engine, LevelChunk chunk, LevelChunkSection section, int x, int y, int z, DataLayer bl, DataLayer sl, IngestBatch batch) {
        if (!shouldIngestSection(section, x, y, z)) return false;
        if (chunk == null) {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedChunkless.increment();
        } else {
            me.cortex.voxy.commonImpl.PerfStats.sectionIngestedWithChunk.increment();
        }
        if (engine.instanceIn == null) return false;
        if (!engine.instanceIn.isIngestEnabled(null)) return false;
        return engine.instanceIn.getIngestService().rawIngest0(engine, chunk, section, x, y, z, bl, sl, batch);
    }

}
