package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Nonblocking server-thread admission; cold reads, serialization and encoding run on workers. */
public final class LodServerPreparation implements AutoCloseable {
    public static final long MAX_BYTES = 32L << 20;
    public static final int MAX_IN_FLIGHT = 64;
    public static final int MAX_ENTRIES = 1024; // Also bounds cached empty replies and world identities.
    private static final int MAX_SECTION_BYTES = 16 + WorldSection.SECTION_VOLUME * (2 + 8);
    private static final long MAPPING_RESERVATION = 2L * LodServerScheduler.PLAYER_BYTES_PER_TICK;
    private static final int MAP_PAGE_ENTRIES = 128;
    private static final int PREFERRED_MAP_BYTES = 64 << 10;

    sealed interface Key permits SectionKey, MappingKey { WorldEngine world(); }
    record SectionKey(WorldEngine world, String dimension, long section) implements Key {}
    record MappingKey(WorldEngine world, boolean blocks, int cursor) implements Key {}
    interface Loader {
        VoxyNetwork.SectionDataS2C section(SectionKey key);
        Object mapping(MappingKey key);
    }
    private enum State { QUEUED, RUNNING, READY }
    private static final class Entry {
        final Key key;
        long revision, bytes;
        State state = State.QUEUED;
        Object result;
        Entry(Key key, long revision, long bytes) { this.key = key; this.revision = revision; this.bytes = bytes; }
    }
    public record Stats(int inFlight, int cachedSections, int cachedMappingPages, long bytes,
                        long peakBytes, int peakInFlight, long sectionsPrepared, long mappingPagesPrepared,
                        long cacheHits, long invalidatedResults, long failures, long refusals) {}

    private final Service service;
    private final Loader loader;
    private final long byteLimit;
    private final int taskLimit, entryLimit;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(128, 0.75f, true);
    private final ArrayDeque<Entry> tasks = new ArrayDeque<>();
    private int inFlight, peakInFlight;
    private long bytes, peakBytes, revision, sectionsPrepared, pagesPrepared, hits, invalidated, failures, refusals;
    private boolean closed;

    public LodServerPreparation(ServiceManager manager) {
        this(manager, new WorldLoader(), MAX_BYTES, MAX_IN_FLIGHT, MAX_ENTRIES);
    }

    LodServerPreparation(ServiceManager manager, Loader loader, long byteLimit, int taskLimit, int entryLimit) {
        if (byteLimit < MAX_SECTION_BYTES || taskLimit < 1 || entryLimit < taskLimit) throw new IllegalArgumentException("Preparation budget");
        this.loader = loader;
        this.byteLimit = byteLimit;
        this.taskLimit = taskLimit;
        this.entryLimit = entryLimit;
        this.service = manager.createServiceNoCleanup(() -> this::processJob, 2, "Server network preparation");
    }

    public VoxyNetwork.SectionDataS2C section(WorldEngine world, LodUpdateQueue.Key key) {
        return (VoxyNetwork.SectionDataS2C) request(new SectionKey(world, key.dimension(), key.section()));
    }

    /** Returns a pending marker until the page exists. Counts/cursors are rechecked on every call. */
    public LodServerScheduler.Mappings mappings(WorldEngine world, int blockCursor, int biomeCursor) {
        var mapper = world.getMapper();
        if (blockCursor < mapper.getBlockStateCount()) {
            var payload = (VoxyNetwork.BlockMapBatchS2C) request(new MappingKey(world, true, blockCursor));
            return new LodServerScheduler.Mappings(payload, payload == null ? blockCursor : blockCursor + payload.entries().size(), biomeCursor);
        }
        if (biomeCursor < mapper.getBiomeCount()) {
            var payload = (VoxyNetwork.BiomeMapBatchS2C) request(new MappingKey(world, false, biomeCursor));
            return new LodServerScheduler.Mappings(payload, blockCursor, payload == null ? biomeCursor : biomeCursor + payload.entries().size());
        }
        return null;
    }

    private synchronized Object request(Key key) {
        if (closed || !key.world().isLive()) return null;
        var entry = entries.get(key);
        if (entry != null) {
            if (entry.state == State.READY) { hits++; return entry.result; }
            return null;
        }
        long reservation = key instanceof SectionKey ? MAX_SECTION_BYTES : MAPPING_RESERVATION;
        if (inFlight == taskLimit || reservation > byteLimit) { refusals++; return null; }
        while (bytes + reservation > byteLimit || entries.size() >= entryLimit) {
            if (!evictReady()) { refusals++; return null; }
        }
        // The task owns this world reference until publication/failure, including while queued.
        key.world().acquireRef();
        entry = new Entry(key, ++revision, reservation);
        entries.put(key, entry);
        tasks.add(entry);
        bytes += reservation;
        inFlight++;
        peakInFlight = Math.max(peakInFlight, inFlight);
        peakBytes = Math.max(peakBytes, bytes);
        service.execute();
        return null;
    }

    private boolean evictReady() {
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.state == State.READY) {
                bytes -= entry.bytes;
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    public synchronized void invalidate(WorldEngine world, LodUpdateQueue.Key key) {
        var cacheKey = new SectionKey(world, key.dimension(), key.section());
        var entry = entries.get(cacheKey);
        if (closed || entry == null) return;
        entry.revision = ++revision;
        if (entry.state == State.READY) {
            entries.remove(cacheKey);
            bytes -= entry.bytes;
        }
    }

    public synchronized boolean current(WorldEngine world, LodUpdateQueue.Key key, VoxyNetwork.SectionDataS2C data) {
        var entry = entries.get(new SectionKey(world, key.dimension(), key.section()));
        return !closed && world.isLive() && entry != null && entry.state == State.READY && entry.result == data;
    }

    private void processJob() {
        Entry entry;
        long startedRevision;
        synchronized (this) {
            if (closed) return;
            entry = tasks.poll();
            if (entry == null) return;
            entry.state = State.RUNNING;
            startedRevision = entry.revision;
        }
        Object result = null;
        try {
            result = switch (entry.key) {
                case SectionKey key -> loader.section(key);
                case MappingKey key -> loader.mapping(key);
            };
            if (result != null && dataSize(result) > entry.bytes) throw new IllegalStateException("Prepared result exceeds reservation");
        } catch (Exception failure) {
            result = null;
            synchronized (this) { failures++; }
            Logger.error("Voxy network preparation failed; request remains retryable", failure);
        } finally {
            try {
                synchronized (this) {
                    inFlight--;
                    bytes -= entry.bytes;
                    if (!closed && result != null && entry.revision == startedRevision) {
                        entry.result = result;
                        entry.bytes = dataSize(result);
                        entry.state = State.READY;
                        bytes += entry.bytes;
                        if (entry.key instanceof SectionKey) sectionsPrepared++;
                        else pagesPrepared++;
                    } else {
                        entries.remove(entry.key);
                        if (entry.revision != startedRevision) invalidated++;
                    }
                }
            } finally { entry.key.world().releaseRef(); }
        }
    }

    private static long dataSize(Object data) {
        return switch (data) {
            case VoxyNetwork.SectionDataS2C p -> p.data().length;
            // Includes both the preencoded bytes and retained mapping entry arrays/text.
            case VoxyNetwork.BlockMapBatchS2C p -> p.encoded().length
                    + p.entries().stream().mapToLong(e -> e.length).sum();
            case VoxyNetwork.BiomeMapBatchS2C p -> p.encoded().length
                    + p.entries().stream().mapToLong(e -> (long) e.length() * 2).sum();
            default -> throw new IllegalArgumentException("Prepared payload type");
        };
    }

    public synchronized Stats stats() {
        int sections = 0, mappings = 0;
        for (var entry : entries.values()) if (entry.state == State.READY) {
            if (entry.key instanceof SectionKey) sections++; else mappings++;
        }
        return new Stats(inFlight, sections, mappings, bytes, peakBytes, peakInFlight,
                sectionsPrepared, pagesPrepared, hits, invalidated, failures, refusals);
    }

    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        service.shutdown(); // Wait for running loaders before releasing queued world references.
        List<Entry> abandoned;
        synchronized (this) {
            abandoned = new ArrayList<>(tasks);
            tasks.clear(); entries.clear(); bytes = 0; inFlight = 0;
        }
        for (var entry : abandoned) entry.key.world().releaseRef();
    }

    static final class WorldLoader implements Loader {
        public VoxyNetwork.SectionDataS2C section(SectionKey key) {
            var section = key.world().acquireIfExists(key.section());
            byte[] data = new byte[0];
            if (section != null) {
                try {
                    var buffer = SaveLoadSystem3.serialize(section).asByteBuffer();
                    data = new byte[buffer.remaining()];
                    buffer.get(data);
                } finally { section.release(); }
            }
            return new VoxyNetwork.SectionDataS2C(key.dimension(), WorldEngine.getLevel(key.section()),
                    WorldEngine.getX(key.section()), WorldEngine.getY(key.section()), WorldEngine.getZ(key.section()), data);
        }

        public Object mapping(MappingKey key) {
            var mapper = key.world().getMapper();
            if (key.blocks()) {
                var entries = new ArrayList<byte[]>();
                int size = VoxyNetwork.encodedSize(new VoxyNetwork.BlockMapBatchS2C(key.cursor(), List.of()));
                for (var state : mapper.getStateEntries(key.cursor(), MAP_PAGE_ENTRIES)) {
                    byte[] encoded = state.serialize();
                    int nextSize = size + VoxyNetwork.varIntSize(encoded.length) + encoded.length;
                    if (entries.size() == 127) nextSize++; // Page count varint grows to two bytes.
                    if (nextSize > PREFERRED_MAP_BYTES && !entries.isEmpty()) break;
                    if (nextSize > LodServerScheduler.PLAYER_BYTES_PER_TICK) throw new IllegalStateException("Mapping entry exceeds player byte budget");
                    entries.add(encoded); size = nextSize;
                }
                return entries.isEmpty() ? null : VoxyNetwork.BlockMapBatchS2C.preencoded(key.cursor(), entries);
            }
            var entries = new ArrayList<String>();
            int size = VoxyNetwork.encodedSize(new VoxyNetwork.BiomeMapBatchS2C(key.cursor(), List.of()));
            for (var biome : mapper.getBiomeEntries(key.cursor(), MAP_PAGE_ENTRIES)) {
                int nextSize = size + VoxyNetwork.stringSize(biome.biome);
                if (entries.size() == 127) nextSize++;
                if (nextSize > PREFERRED_MAP_BYTES && !entries.isEmpty()) break;
                entries.add(biome.biome); size = nextSize;
            }
            return entries.isEmpty() ? null : VoxyNetwork.BiomeMapBatchS2C.preencoded(key.cursor(), entries);
        }
    }
}
