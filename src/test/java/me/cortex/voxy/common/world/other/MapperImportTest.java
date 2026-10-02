package me.cortex.voxy.common.world.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.IMappingStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MapperImportTest {
    @Test
    void importedVariantsRemainDistinctAndSurviveReload() {
        var storage = new MappingStorage();
        var mapper = new Mapper(storage);
        var state = Blocks.STONE.defaultBlockState();
        int plainId = mapper.getIdForBlockState(state);
        var data = new CompoundTag();
        data.putString("material", "minecraft:oak_planks");
        var serverEntry = new Mapper.StateEntry(91, state, "test_variant", "oak", data);
        // Use the same compressed mapping representation as the network protocol.
        var received = Mapper.StateEntry.deserialize(91, serverEntry.serialize(), new boolean[1]);
        int localId = mapper.importStateEntry(received);
        assertNotEquals(plainId, localId);
        assertNotEquals(91, localId);
        assertEquals(localId, mapper.importStateEntry(received));
        assertNotEquals(localId, mapper.importStateEntry(
                new Mapper.StateEntry(92, state, "test_variant", "birch", new CompoundTag())));
        mapper.close();

        var reloaded = new Mapper(storage);
        var restored = Arrays.stream(reloaded.getStateEntries()).filter(e -> e.id == localId).findFirst().orElseThrow();
        assertEquals("test_variant", restored.variantType);
        assertEquals("oak", restored.variantKey);
        assertEquals(data, restored.variantData);
        assertEquals(localId, reloaded.importStateEntry(received));
        reloaded.close();
    }

    @Test
    void ordinaryMappingsAndAirKeepTheirExistingIds() {
        var mapper = new Mapper(new MappingStorage());
        var stone = Blocks.STONE.defaultBlockState();
        assertEquals(mapper.getIdForBlockState(stone), mapper.importStateEntry(new Mapper.StateEntry(91, stone)));
        assertEquals(0, mapper.importStateEntry(new Mapper.StateEntry(92, Blocks.AIR.defaultBlockState())));
        mapper.close();
    }

    private static final class MappingStorage implements IMappingStorage {
        private final Int2ObjectOpenHashMap<byte[]> entries = new Int2ObjectOpenHashMap<>();

        @Override
        public void putIdMapping(int id, ByteBuffer data) {
            byte[] bytes = new byte[data.remaining()];
            data.duplicate().get(bytes);
            entries.put(id, bytes);
        }

        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return entries.clone(); }
        @Override public void flush() {}
        @Override public void close() {}
    }
}
