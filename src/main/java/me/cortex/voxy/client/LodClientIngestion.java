package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.network.LodMappingTranslator;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** One connection's bounded, serial consumer. Admission and confirmations run on the client thread. */
public final class LodClientIngestion implements AutoCloseable {
    static final long MAX_BYTES = 64L << 20;
    static final int MAX_TASKS = 1024;
    static final long CONFIRM_NANOS = 1_000_000;
    private static final int MAX_SECTION_BYTES = 16 + WorldSection.SECTION_VOLUME * 10;
    private static final long DECODE_RESERVATION = 16L * WorldSection.SECTION_VOLUME;
    private static final int MAX_BLOCK_IDS = 1 << 20, MAX_BIOME_IDS = 512;

    enum Result { APPLIED, MISSING, WAIT_FOR_MAPPINGS, RETRY }
    record Snapshot(int[] blocks, int[] biomes, long revision) {
        static final Snapshot EMPTY = new Snapshot(new int[0], new int[0], 0);
        long bytes() { return 4L * (blocks.length + biomes.length); }
    }
    record Decoded(Result result, long[] voxels, byte children, int knownOctants, int nonEmpty) {
        static Decoded result(Result result) { return new Decoded(result, null, (byte) 0, 0, 0); }
    }
    interface Processor {
        Decoded decode(VoxyNetwork.SectionDataS2C payload, Snapshot mappings);
        Snapshot mapping(WorldEngine world, Snapshot previous, Object payload);
        void apply(WorldEngine world, VoxyNetwork.SectionDataS2C payload, Decoded decoded);
    }
    interface Confirmations {
        void section(LodSectionRequests.Request request, Result result);
        void mappings(String dimension, Snapshot snapshot);
    }
    public record Stats(int tasks, int queued, int waiting, int confirmations, long bytes, long peakBytes,
                 int peakTasks, long applied, long coalesced, long refused, long stale, long failures) {}
    private enum State { QUEUED, RUNNING, WAITING, DONE }
    private static final class Entry {
        long desired;
        Job active, next;
    }
    private static final class Job {
        final WorldEngine world;
        final String dimension;
        final LodSectionRequests.Request request;
        final Entry entry;
        long version, bytes;
        Object payload;
        State state = State.QUEUED;
        Result result;
        Snapshot snapshot;
        boolean ownsWorld = true;
        Job(WorldEngine world, String dimension, LodSectionRequests.Request request, Entry entry, Object payload, long bytes, long version) {
            this.world = world; this.dimension = dimension; this.request = request; this.entry = entry;
            this.payload = payload; this.bytes = bytes; this.version = version;
        }
    }

    private final Service service;
    private final Function<String, WorldEngine> worlds;
    private final Processor processor;
    private final Confirmations confirmations;
    private final LongSupplier clock;
    private final long byteLimit;
    private final int taskLimit;
    private final Map<LodSectionRequests.Request, Entry> sections = new HashMap<>();
    private final Map<WorldEngine, Snapshot> staged = new HashMap<>(), published = new HashMap<>();
    private final Map<WorldEngine, int[]> promisedSizes = new HashMap<>();
    private final ArrayDeque<Job> runnable = new ArrayDeque<>(), completed = new ArrayDeque<>();
    private int tasks, peakTasks;
    private long bytes, peakBytes, sequence, applied, coalesced, refused, stale, failures;
    private boolean scheduled, closed, mappingRepair, saturated;

    LodClientIngestion(ServiceManager manager, Function<String, WorldEngine> worlds, Confirmations confirmations) {
        this(manager, worlds, confirmations, new WorldProcessor(), MAX_BYTES, MAX_TASKS, System::nanoTime);
    }

    LodClientIngestion(ServiceManager manager, Function<String, WorldEngine> worlds, Confirmations confirmations,
                       Processor processor, long byteLimit, int taskLimit, LongSupplier clock) {
        this.worlds = worlds; this.confirmations = confirmations; this.processor = processor;
        this.byteLimit = byteLimit; this.taskLimit = taskLimit; this.clock = clock;
        // Exactly one token is scheduled, independent of the number of pool/helper threads.
        this.service = manager.createServiceNoCleanup(() -> this::runOne, 2, "Client network ingestion");
    }

    boolean section(VoxyNetwork.SectionDataS2C payload, LodSectionRequests.Request request) {
        WorldEngine world = null;
        try { world = worlds.apply(payload.dimension()); }
        catch (Exception failure) { Logger.error("Voxy failed resolving the terrain world", failure); }
        synchronized (this) {
            if (closed) return false;
            pruneWorlds();
            Entry entry = sections.computeIfAbsent(request, ignored -> new Entry());
            entry.desired = ++sequence; // Even a refused successor invalidates the old confirmation.
            Job replacing = entry.next != null ? entry.next : entry.active;
            if (replacing != null && replacing.state != State.RUNNING) {
                discard(replacing); coalesced++;
                if (entry.next == replacing) entry.next = null; else entry.active = null;
            }
            long charge = payload.data().length == 0 ? 0 : payload.data().length + DECODE_RESERVATION;
            // Leave admission room for mappings: otherwise a full queue waiting for IDs cannot recover.
            long mappingRoom = Math.min(16L << 20, byteLimit / 4);
            int mappingSlots = Math.min(16, taskLimit / 16);
            boolean capacity = tasks < taskLimit - mappingSlots && charge <= byteLimit - mappingRoom - bytes;
            if (world == null || !world.isLive() || payload.data().length > MAX_SECTION_BYTES
                    || !capacity) {
                if (!capacity) saturated = true;
                refused++;
                if (entry.active == null) sections.remove(request);
                return false;
            }
            world.acquireRef();
            Job job = new Job(world, payload.dimension(), request, entry, payload, charge, entry.desired);
            retain(job);
            if (entry.active == null) { entry.active = job; runnable.add(job); }
            else { entry.next = job; coalesced++; }
            schedule();
            return true;
        }
    }

    boolean mapping(String dimension, Object payload) {
        WorldEngine world = worlds.apply(dimension);
        synchronized (this) {
            if (closed) return false;
            pruneWorlds();
            if (world == null || !world.isLive()) { mappingRepair = true; return false; }
            var sizes = promisedSizes.computeIfAbsent(world, ignored -> new int[2]);
            long input;
            int type, end;
            switch (payload) {
                case VoxyNetwork.BlockMapBatchS2C p -> {
                    type = 0; end = checkedEnd(p.startId(), p.entries().size(), MAX_BLOCK_IDS);
                    input = p.entries().stream().mapToLong(e -> e.length).sum();
                }
                case VoxyNetwork.BiomeMapBatchS2C p -> {
                    type = 1; end = checkedEnd(p.startId(), p.entries().size(), MAX_BIOME_IDS);
                    input = p.entries().stream().mapToLong(e -> 2L * e.length()).sum();
                }
                default -> throw new IllegalArgumentException("Mapping payload");
            }
            long charge = input + 8L * (Math.max(sizes[type], end) + sizes[1 - type]);
            if (!fits(charge)) { refused++; mappingRepair = true; saturated = true; return false; }
            sizes[type] = Math.max(sizes[type], end);
            world.acquireRef();
            Job job = new Job(world, dimension, null, null, payload, charge, ++sequence);
            retain(job); runnable.add(job); schedule();
            return true;
        }
    }

    private static int checkedEnd(int start, int count, int max) {
        if (start < 0 || count < 0 || (long) start + count > max) throw new IllegalArgumentException("Mapping ID range");
        return start + count;
    }
    private boolean fits(long charge) { return tasks < taskLimit && charge <= byteLimit - bytes; }
    private void retain(Job job) {
        tasks++; bytes += job.bytes; peakTasks = Math.max(peakTasks, tasks); peakBytes = Math.max(peakBytes, bytes);
    }
    private void releaseWorld(Job job) {
        if (job.ownsWorld) { job.ownsWorld = false; job.world.releaseRef(); }
    }
    private void discard(Job job) {
        runnable.remove(job); completed.remove(job); bytes -= job.bytes; tasks--;
        job.payload = null; job.snapshot = null; releaseWorld(job);
    }
    private void schedule() {
        if (!closed && !scheduled && !runnable.isEmpty()) { scheduled = true; service.execute(); }
    }

    private void runOne() {
        Job job;
        Snapshot snapshot;
        synchronized (this) {
            if (closed) { scheduled = false; return; }
            job = runnable.poll();
            if (job == null) { scheduled = false; return; }
            job.state = State.RUNNING;
            snapshot = staged.getOrDefault(job.world, Snapshot.EMPTY);
        }
        Result result = Result.RETRY;
        Snapshot mapped = null;
        try {
            if (job.request == null) mapped = processor.mapping(job.world, snapshot, job.payload);
            else {
                var payload = (VoxyNetwork.SectionDataS2C) job.payload;
                var decoded = processor.decode(payload, snapshot);
                result = decoded.result();
                if (result == Result.APPLIED) {
                    // Cold reads must not hold the admission lock. Acquire before the final commit gate.
                    var section = job.world.acquire(payload.lvl(), payload.x(), payload.y(), payload.z());
                    try {
                        synchronized (this) {
                            if (!closed && job.version == job.entry.desired) processor.apply(job.world, payload, decoded);
                            else result = Result.RETRY;
                        }
                    } finally { section.release(); }
                }
            }
        } catch (Exception failure) {
            result = Result.RETRY;
            synchronized (this) { failures++; if (job.request == null) mappingRepair = true; }
            Logger.error("Voxy client network processing failed; terrain remains retryable", failure);
        } finally {
            synchronized (this) {
                if (job.request == null) {
                    if (!closed && mapped != null) {
                        replaceSnapshot(staged, job.world, mapped);
                        bytes -= job.bytes; job.bytes = mapped.bytes(); bytes += job.bytes;
                        job.snapshot = mapped; job.payload = null; job.state = State.DONE; completed.add(job);
                        mappingRepair = false;
                        // Wake only on a mapping notification, never by polling missing IDs.
                        for (var entry : sections.values()) {
                            var waiting = entry.active;
                            if (waiting != null && waiting.world == job.world && waiting.state == State.WAITING) {
                                waiting.state = State.QUEUED; runnable.add(waiting);
                            }
                        }
                        peakBytes = Math.max(peakBytes, bytes);
                        releaseWorld(job);
                    } else discard(job);
                } else if (closed || job.version != job.entry.desired) {
                    stale++; discard(job);
                    job.entry.active = job.entry.next; job.entry.next = null;
                    if (job.entry.active != null) runnable.add(job.entry.active);
                    else sections.remove(job.request);
                } else if (result == Result.WAIT_FOR_MAPPINGS) {
                    job.state = State.WAITING;
                } else {
                    bytes -= job.bytes; job.bytes = 0; job.payload = null;
                    job.result = result; job.state = State.DONE; completed.add(job); releaseWorld(job);
                    if (result == Result.APPLIED) applied++;
                }
                scheduled = false; schedule();
            }
        }
    }

    private void replaceSnapshot(Map<WorldEngine, Snapshot> table, WorldEngine world, Snapshot snapshot) {
        var previous = table.put(world, snapshot);
        bytes += snapshot.bytes() - (previous == null ? 0 : previous.bytes());
    }

    private void pruneWorlds() {
        for (var table : List.of(staged, published)) {
            table.entrySet().removeIf(e -> {
                if (e.getKey().isLive()) return false;
                bytes -= e.getValue().bytes(); return true;
            });
        }
        promisedSizes.keySet().removeIf(world -> !world.isLive());
    }

    /** Main-thread completion budget is checked between callbacks. No voxel work is performed here. */
    int drain(long budgetNanos) {
        long start = clock.getAsLong();
        int count = 0;
        while (clock.getAsLong() - start < budgetNanos) {
            Job job;
            synchronized (this) {
                if (closed || (job = completed.poll()) == null) break;
                if (job.request == null) replaceSnapshot(published, job.world, job.snapshot);
                else sections.remove(job.request);
                // Snapshot bytes move from the completion reservation to the published table.
                bytes -= job.bytes; tasks--;
            }
            if (job.request == null && job.world.isLive()) confirmations.mappings(job.dimension, job.snapshot);
            else if (job.request == null) { synchronized (this) { mappingRepair = true; pruneWorlds(); } }
            else confirmations.section(job.request, job.result);
            count++;
        }
        return count;
    }

    synchronized boolean needsMappings() {
        return mappingRepair || sections.values().stream().anyMatch(e -> e.active != null && e.active.state == State.WAITING);
    }
    synchronized List<LodSectionRequests.Request> sectionKeys() { return List.copyOf(sections.keySet()); }
    synchronized boolean pressured() {
        pruneWorlds();
        if (bytes >= byteLimit * 3 / 4 || tasks >= taskLimit * 3 / 4) saturated = true;
        else if (bytes < byteLimit / 2 && tasks < taskLimit / 2) saturated = false;
        return saturated;
    }
    synchronized Stats stats() {
        int waiting = (int) sections.values().stream().filter(e -> e.active != null && e.active.state == State.WAITING).count();
        return new Stats(tasks, runnable.size(), waiting, completed.size(), bytes, peakBytes, peakTasks,
                applied, coalesced, refused, stale, failures);
    }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        service.shutdown();
        synchronized (this) {
            for (var entry : sections.values()) {
                if (entry.active != null) releaseWorld(entry.active);
                if (entry.next != null) releaseWorld(entry.next);
            }
            for (var job : runnable) releaseWorld(job);
            for (var job : completed) releaseWorld(job);
            sections.clear(); runnable.clear(); completed.clear(); staged.clear(); published.clear(); promisedSizes.clear();
            bytes = 0; tasks = 0;
        }
    }

    static final class WorldProcessor implements Processor {
        public Decoded decode(VoxyNetwork.SectionDataS2C payload, Snapshot mappings) {
            byte[] data = payload.data();
            if (data.length == 0) return Decoded.result(Result.MISSING);
            if (data.length < 16 + WorldSection.SECTION_VOLUME * 2 || data.length > MAX_SECTION_BYTES)
                throw new IllegalArgumentException("Truncated section");
            var buf = ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
            long key = WorldEngine.getWorldSectionId(payload.lvl(), payload.x(), payload.y(), payload.z());
            if (buf.getLong() != key) throw new IllegalArgumentException("Section key mismatch");
            long metadata = buf.getLong();
            int count = (int) (metadata & 65535);
            if (count == 0 || count > WorldSection.SECTION_VOLUME || data.length != 16 + WorldSection.SECTION_VOLUME * 2 + count * 8)
                throw new IllegalArgumentException("Section palette size");
            long[] palette = new long[count];
            int paletteOffset = 16 + WorldSection.SECTION_VOLUME * 2;
            for (int i = 0; i < count; i++) {
                palette[i] = LodMappingTranslator.translate(buf.getLong(paletteOffset + i * 8), mappings.blocks(), mappings.biomes());
                if (palette[i] == Mapper.UNKNOWN_MAPPING) return Decoded.result(Result.WAIT_FOR_MAPPINGS);
            }
            long[] voxels = new long[WorldSection.SECTION_VOLUME];
            int nonEmpty = 0;
            for (int i = 0; i < voxels.length; i++) {
                int index = Short.toUnsignedInt(buf.getShort());
                if (index >= count) throw new IllegalArgumentException("Section palette index");
                voxels[i] = palette[index];
                if (!Mapper.isAir(voxels[i])) nonEmpty++;
            }
            return new Decoded(Result.APPLIED, voxels, (byte) (metadata >>> 16), SaveLoadSystem3.decodeKnownOctants(metadata), nonEmpty);
        }

        public Snapshot mapping(WorldEngine world, Snapshot previous, Object payload) {
            int[] blocks = previous.blocks(), biomes = previous.biomes();
            var mapper = world.getMapper();
            switch (payload) {
                case VoxyNetwork.BlockMapBatchS2C p -> {
                    blocks = growCopy(blocks, p.startId() + p.entries().size());
                    for (int i = 0; i < p.entries().size(); i++) {
                        int id = p.startId() + i;
                        blocks[id] = mapper.importStateEntry(Mapper.StateEntry.deserialize(id, p.entries().get(i), new boolean[1]));
                    }
                }
                case VoxyNetwork.BiomeMapBatchS2C p -> {
                    biomes = growCopy(biomes, p.startId() + p.entries().size());
                    for (int i = 0; i < p.entries().size(); i++) biomes[p.startId() + i] = mapper.getOrCreateBiomeId(p.entries().get(i));
                }
                default -> throw new IllegalArgumentException("Mapping payload");
            }
            return new Snapshot(blocks, biomes, previous.revision() + 1);
        }

        private static int[] growCopy(int[] previous, int end) {
            var copy = Arrays.copyOf(previous, Math.max(previous.length, end));
            Arrays.fill(copy, previous.length, copy.length, -1);
            return copy;
        }

        public void apply(WorldEngine world, VoxyNetwork.SectionDataS2C payload, Decoded decoded) {
            var section = world.acquire(payload.lvl(), payload.x(), payload.y(), payload.z());
            try {
                synchronized (section) {
                    System.arraycopy(decoded.voxels(), 0, section._unsafeGetRawDataArray(), 0, WorldSection.SECTION_VOLUME);
                    section._unsafeSetNonEmptyChildren(decoded.children());
                    section.setKnownOctants(decoded.knownOctants());
                    if (payload.lvl() == 0) section.addNonEmptyBlockCount(decoded.nonEmpty() - section.getNonEmptyBlockCount());
                }
                world.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS, 0);
                if (world.storage instanceof SectionSerializationStorage serializer) serializer.forgetMissingSection(section.key);
            } finally { section.release(); }
            world.markActive();
        }
    }
}
