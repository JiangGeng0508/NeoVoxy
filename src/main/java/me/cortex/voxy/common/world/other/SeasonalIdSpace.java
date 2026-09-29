package me.cortex.voxy.common.world.other;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

import java.util.concurrent.ConcurrentHashMap;

public final class SeasonalIdSpace {
    public static final int MAX_BLOCK_ID = 0xFFFFF;
    public static final int VIRTUAL_ICE_ID = MAX_BLOCK_ID;
    public static final int FIRST_SEASONAL_ID = 0x80000;

    private SeasonalIdSpace() {}

    public record Entry(int originalBlockId, ResourceLocation modelId, boolean snowy) { }

    //Concurrent maps so the mesh-worker hit paths (getOrCreate on every seasonal-model voxel of
    //a canopy section, decode on every legacy complement id) never take the class lock; the lock
    //only serialises id allocation
    private static final ConcurrentHashMap<Entry, Integer> KEY_TO_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Entry> ID_TO_ENTRY = new ConcurrentHashMap<>();
    private static int nextId = FIRST_SEASONAL_ID;

    //Lazy: StateEntry's constructor probes the block, which needs registries up
    private static Mapper.StateEntry virtualIceEntry;

    public static synchronized Mapper.StateEntry virtualIceEntry() {
        if (virtualIceEntry == null) {
            virtualIceEntry = new Mapper.StateEntry(VIRTUAL_ICE_ID, Blocks.ICE.defaultBlockState());
        }
        return virtualIceEntry;
    }

    public static int getOrCreate(Mapper mapper, int originalBlockId,
                                  ResourceLocation modelId, boolean snowy) {
        var key = new Entry(originalBlockId, modelId, snowy);
        Integer existing = KEY_TO_ID.get(key);
        if (existing != null) return existing;
        synchronized (SeasonalIdSpace.class) {
            existing = KEY_TO_ID.get(key);
            if (existing != null) return existing;
            int count = mapper.getBlockStateCount();
            if (count >= FIRST_SEASONAL_ID) return originalBlockId;
            if (nextId >= MAX_BLOCK_ID - count) return originalBlockId;
            int id = nextId++;
            //Entry before key: a decoder that can see the id must be able to resolve it
            ID_TO_ENTRY.put(id, key);
            KEY_TO_ID.put(key, id);
            return id;
        }
    }

    public static Entry get(int blockId) {
        return ID_TO_ENTRY.get(blockId);
    }

    public static int decode(Mapper mapper, int blockId) {
        int count = mapper.getBlockStateCount();
        if (blockId < count) return blockId;
        if (blockId == VIRTUAL_ICE_ID) return blockId;
        var seasonal = get(blockId);
        if (seasonal != null) return seasonal.originalBlockId();
        int complement = MAX_BLOCK_ID - blockId;
        if (complement >= 0 && complement < count) return complement;
        return blockId;
    }

    public static boolean resolvesToState(Mapper mapper, int blockId) {
        return blockId == VIRTUAL_ICE_ID || decode(mapper, blockId) < mapper.getBlockStateCount();
    }
}
