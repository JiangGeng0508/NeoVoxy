package me.cortex.voxy.client.distgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.common.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Persistent "already generated" bitmap for distant generation: one bit per chunk,
 * grouped into 32x32-chunk regions (matching .mca granularity). Lets the generator
 * skip chunks it already ingested in previous sessions without probing the voxel DB.
 *
 * Format: [int magic][int version] then repeated [long regionKey][16 longs bitmap].
 * Thread-safe: marks may arrive from ingest worker threads (distant gen batch callbacks)
 * while the server thread reads/saves.
 */
public class GeneratedChunkBitmap {
    private static final int MAGIC = 0x56594447;//"VYDG"
    //Version 2: older builds had a bug where chunks that failed/gave up ingesting were still
    // marked done in here, permanently poisoning those entries as skip-forever holes in the
    // LOD. Bumping the version invalidates every bitmap written by those builds so the
    // affected area gets regenerated (and correctly ingested) by the fixed pipeline.
    private static final int VERSION = 2;
    private static final int LONGS_PER_REGION = (32*32)/64;

    private final Path file;
    private final Long2ObjectOpenHashMap<long[]> regions = new Long2ObjectOpenHashMap<>();
    private boolean dirty;
    private int count;

    public GeneratedChunkBitmap(Path file) {
        this.file = file;
        this.load();
    }

    private static long regionKey(int cx, int cz) {
        return (Integer.toUnsignedLong(cz>>5)<<32)|Integer.toUnsignedLong(cx>>5);
    }

    private static int bitIndex(int cx, int cz) {
        return ((cz&31)<<5)|(cx&31);
    }

    public synchronized boolean contains(int cx, int cz) {
        long[] region = this.regions.get(regionKey(cx, cz));
        if (region == null) {
            return false;
        }
        int idx = bitIndex(cx, cz);
        return (region[idx>>6]&(1L<<(idx&63))) != 0;
    }

    public synchronized void mark(int cx, int cz) {
        long[] region = this.regions.computeIfAbsent(regionKey(cx, cz), k->new long[LONGS_PER_REGION]);
        int idx = bitIndex(cx, cz);
        long mask = 1L<<(idx&63);
        if ((region[idx>>6]&mask) == 0) {
            region[idx>>6] |= mask;
            this.count++;
            this.dirty = true;
        }
    }

    public synchronized int getCount() {
        return this.count;
    }

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                Logger.warn("Distant generation bitmap has unknown format, starting fresh: " + this.file);
                return;
            }
            while (in.available() > 0) {
                long key = in.readLong();
                long[] region = new long[LONGS_PER_REGION];
                for (int i = 0; i < LONGS_PER_REGION; i++) {
                    region[i] = in.readLong();
                    this.count += Long.bitCount(region[i]);
                }
                this.regions.put(key, region);
            }
        } catch (IOException e) {
            Logger.error("Failed to load distant generation bitmap, starting fresh", e);
            this.regions.clear();
            this.count = 0;
        }
    }

    public synchronized void saveIfDirty() {
        if (!this.dirty) {
            return;
        }
        try {
            Files.createDirectories(this.file.getParent());
            var tmp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                for (var entry : this.regions.long2ObjectEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    for (long word : entry.getValue()) {
                        out.writeLong(word);
                    }
                }
            }
            Files.move(tmp, this.file, StandardCopyOption.REPLACE_EXISTING);
            this.dirty = false;
        } catch (IOException e) {
            Logger.error("Failed to save distant generation bitmap", e);
        }
    }

    public synchronized void reset() {
        this.regions.clear();
        this.count = 0;
        this.dirty = false;
        try {
            Files.deleteIfExists(this.file);
        } catch (IOException e) {
            Logger.error("Failed to delete distant generation bitmap", e);
        }
    }
}
