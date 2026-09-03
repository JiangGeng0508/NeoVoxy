package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.LongConsumer;

public class SectionSerializationStorage extends SectionStorage {
    public static final int BIGGEST_SERIALIZED_SECTION_SIZE = 32 * 32 * 32 * 8 * 2 + 8;
    private static final int MISS_CACHE_SIZE = Integer.getInteger("voxy.storageMissCacheSize", 1 << 16);

    /** Hook for remote fetching: invoked (any thread) on a store miss. Set by client netcode, null on servers. */
    public interface ISectionMissListener { void onMissing(long sectionKey); }
    public static volatile ISectionMissListener MISS_LISTENER;

    private final StorageBackend backend;
    private final LongLinkedOpenHashSet missingSections = new LongLinkedOpenHashSet();
    public SectionSerializationStorage(StorageBackend storageBackend) {
        this.backend = storageBackend;
    }

    private static final ThreadLocalMemoryBuffer MEMORY_CACHE = new ThreadLocalMemoryBuffer(BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    public int loadSection(WorldSection into) {
        if (this.isKnownMissing(into.key)) {
            return 1;
        }

        var data = this.backend.getSectionData(into.key, MEMORY_CACHE.get().createUntrackedUnfreeableReference());
        if (data != null) {
            this.forgetMissing(into.key);
            if (!SaveLoadSystem3.deserialize(into, data)) {
                this.backend.deleteSectionData(into.key);
                this.rememberMissing(into.key);
                //TODO: regenerate the section from children
                Arrays.fill(into._unsafeGetRawDataArray(), Mapper.AIR);
                Logger.error("Section " + into.lvl + ", " + into.x + ", " + into.y + ", " + into.z + " was unable to load, removing");
                return -1;
            } else {
                return 0;
            }
        } else {
            //TODO: if we need to fetch an lod from a server, send the request here and block until the request is finished
            // the response should be put into the local db so that future data can just use that
            // the server can also send arbitrary updates to the client for arbitrary lods
            var listener = MISS_LISTENER;
            if (listener != null) {
                try {
                    listener.onMissing(into.key);
                } catch (Exception e) {
                    Logger.error("Voxy section miss listener failed", e);
                }
            }
            this.rememberMissing(into.key);
            return 1;
        }
    }

    private boolean isKnownMissing(long key) {
        if (MISS_CACHE_SIZE <= 0) {
            return false;
        }
        synchronized (this.missingSections) {
            return this.missingSections.contains(key);
        }
    }

    private void rememberMissing(long key) {
        if (MISS_CACHE_SIZE <= 0) {
            return;
        }
        synchronized (this.missingSections) {
            if (this.missingSections.addAndMoveToFirst(key)) {
                while (this.missingSections.size() > MISS_CACHE_SIZE) {
                    this.missingSections.removeLastLong();
                }
            }
        }
    }

    private void forgetMissing(long key) {
        if (MISS_CACHE_SIZE <= 0) {
            return;
        }
        synchronized (this.missingSections) {
            this.missingSections.remove(key);
        }
    }

    /** Clears the negative cache for a section supplied from elsewhere (e.g. server sync). */
    public void forgetMissingSection(long key) {
        this.forgetMissing(key);
    }

    @Override
    public void saveSection(WorldSection section) {
        var saveData = SaveLoadSystem3.serialize(section);
        this.backend.setSectionData(section.key, saveData);
        this.forgetMissing(section.key);
        //Note that savedData isnt freed (the save system uses a cache)
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        this.backend.putIdMapping(id, data);
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        return this.backend.getIdMappingsData();
    }

    @Override
    public void flush() {
        this.backend.flush();
    }

    @Override
    public void close() {
        this.backend.close();
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        this.backend.iteratePositions(level, consumer);
    }

    public static class Config extends SectionStorageConfig {
        public StorageConfig storage;

        @Override
        public SectionStorage build(ConfigBuildCtx ctx) {
            return new SectionSerializationStorage(this.storage.build(ctx));
        }

        public static String getConfigTypeName() {
            return "Serializer";
        }
    }
}
