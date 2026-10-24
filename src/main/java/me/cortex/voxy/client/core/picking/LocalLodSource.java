package me.cortex.voxy.client.core.picking;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** One request's bounded local snapshots. Caller pins the engine until close(). */
public final class LocalLodSource implements LodRaycaster.Source {
    private record Snapshot(long[] data, long uniform, int known) {
        long get(int x, int y, int z) { return this.data == null ? this.uniform : this.data[WorldSection.getIndex(x, y, z)]; }
        boolean known(int x, int y, int z) { return (this.known & (1 << ((x >> 4) | ((z >> 4) << 1) | ((y >> 4) << 2)))) != 0; }
    }
    private static final Snapshot MISSING = new Snapshot(null, Mapper.AIR, 0);
    private final WorldEngine engine;
    private final boolean crouching;
    private final BooleanSupplier cancelled;
    private final long deadline;
    private final int maxReads;
    // At most 16 dense snapshots (~4 MiB), including negative cache entries.
    private final Map<Long, Snapshot> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final Snapshot[] prepared = new Snapshot[5];
    private final Map<Integer, LodRaycaster.Material> materials = new LinkedHashMap<>();
    private int reads, finest;

    public LocalLodSource(WorldEngine engine, boolean crouching, BooleanSupplier cancelled, long deadline, int maxReads) {
        this.engine = engine;
        this.crouching = crouching;
        this.cancelled = cancelled;
        this.deadline = deadline;
        this.maxReads = maxReads;
    }

    @Override public void prepare(int sx, int sy, int sz) {
        this.finest = 5;
        for (int lod = 0; lod <= 4; lod++) {
            this.prepared[lod] = load(lod, sx >> lod, sy >> lod, sz >> lod);
            if (this.finest == 5 && this.prepared[lod] != MISSING) this.finest = lod;
        }
    }

    private Snapshot load(int lod, int x, int y, int z) {
        if (this.cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return MISSING;
        if (System.nanoTime() >= this.deadline) throw new LodRaycaster.LimitReached();
        long key = WorldEngine.getWorldSectionId(lod, x, y, z);
        Snapshot cached = this.cache.get(key);
        if (cached != null) return cached;
        if (this.reads >= this.maxReads) throw new LodRaycaster.LimitReached();
        this.reads++;
        // Cache promotion can block or reload on an eviction race: worker thread only.
        WorldSection section = this.engine.acquireIfCachedLocal(lod, x, y, z);
        boolean fromStorage = section == null;
        if (fromStorage) {
            section = WorldSection._createRawUntrackedUnsafeSection(lod, x, y, z);
            section.acquire();
        }
        Snapshot snapshot;
        try {
            int status = fromStorage ? this.engine.storage.loadSectionLocal(section) : 0;
            if (status < 0) throw new IllegalStateException("Local LOD read failed: " + WorldEngine.pprintPos(key));
            if (status != 0) snapshot = MISSING;
            else synchronized (section) {
                long[] data = null;
                long uniform = section.getUniformValue();
                if (!section.isUniform()) {
                    data = new long[WorldSection.SECTION_VOLUME];
                    for (int i = 0; i < data.length; i++) data[i] = section.get(i);
                }
                snapshot = new Snapshot(data, uniform, section.getKnownOctants());
            }
        } finally {
            section.release();
        }
        if (this.cache.size() == 16) this.cache.remove(this.cache.keySet().iterator().next());
        this.cache.put(key, snapshot);
        return snapshot;
    }

    @Override public int finestLevel() { return this.finest; }

    @Override public LodRaycaster.Cell uniform() {
        // A fully known uniform finest snapshot also covers this region at coarse LODs.
        // Skipping its cells keeps long rays through known empty terrain inexpensive.
        if (this.finest > 4) return null;
        Snapshot fine = this.prepared[this.finest];
        if (fine == MISSING || fine.data != null || fine.known != 255) return null;
        return new LodRaycaster.Cell(material(fine.uniform), this.finest);
    }

    @Override public LodRaycaster.Cell sample(int bx, int by, int bz) {
        for (int lod = 0; lod <= 4; lod++) {
            Snapshot s = this.prepared[lod];
            if (s == MISSING) continue;
            int x = (bx >> lod) & 31, y = (by >> lod) & 31, z = (bz >> lod) & 31;
            long value = s.get(x, y, z);
            if (!Mapper.isAir(value) || s.known(x, y, z)) return new LodRaycaster.Cell(material(value), lod);
        }
        return new LodRaycaster.Cell(LodRaycaster.Material.UNKNOWN, -1);
    }

    private LodRaycaster.Material material(long value) {
        if (Mapper.isAir(value)) return LodRaycaster.Material.AIR;
        int id = Mapper.getBlockId(value);
        if (this.materials.size() >= 1024 && !this.materials.containsKey(id))
            this.materials.remove(this.materials.keySet().iterator().next());
        return this.materials.computeIfAbsent(id, ignored -> {
            BlockState state;
            try { state = this.engine.getMapper().getBlockStateForLocalReader(id); }
            catch (RuntimeException invalidMapping) { return LodRaycaster.Material.UNKNOWN; }
            if (state == null) return LodRaycaster.Material.UNKNOWN;
            if (this.crouching && !state.getFluidState().isEmpty()) return LodRaycaster.Material.SOLID;
            // Match VISUAL/NONE vs OUTLINE/ANY using the immutable empty-world shape.
            // Context-dependent mod shapes fail conservatively; no live ClientLevel is accessed.
            try {
                var shape = this.crouching ? state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                        : state.getVisualShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, net.minecraft.world.phys.shapes.CollisionContext.empty());
                return shape.isEmpty() ? LodRaycaster.Material.AIR : LodRaycaster.Material.SOLID;
            } catch (RuntimeException unsupportedShape) {
                org.slf4j.LoggerFactory.getLogger("voxy").debug("Unsupported LOD picking shape: {}", state, unsupportedShape);
                return LodRaycaster.Material.UNKNOWN;
            }
        });
    }

    @Override public int reads() { return this.reads; }
    @Override public void close() { this.cache.clear(); this.materials.clear(); java.util.Arrays.fill(this.prepared, null); }
}
