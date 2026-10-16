package me.cortex.voxy.client.compat.create;

import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.block.connected.CTModel;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import me.cortex.voxy.client.core.model.LodBlockAndTintGetter;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Resolves Create casing textures from stored LOD neighbours, even for imported/old terrain. */
public final class CreateConnectedTextures {
    public static final String VARIANT_TYPE = "create_connected_texture";
    private static final boolean INSTALLED = ModList.get().isLoaded("create");
    private static volatile boolean failed;

    // One instance per mesh worker; neither live chunks nor model data survive a renderer reload.
    private final Int2ByteOpenHashMap candidates = new Int2ByteOpenHashMap();
    private final Map<TextureKey, Integer> variants = new HashMap<>();

    private record TextureKey(BlockState state, String indices) { }

    public static boolean enabled() { return INSTALLED && !failed; }

    public long[] substitute(WorldEngine world, WorldSection section, long[] raw) {
        if (!enabled()) return raw;
        Mapper mapper = world.getMapper();
        long[] result = raw;
        try (var view = new LodBlockAndTintGetter(world, section, raw)) {
            var pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < raw.length; i++) {
                int blockId = Mapper.getBlockId(raw[i]);
                if (blockId == 0) continue;
                byte candidate = this.candidates.get(blockId);
                if (candidate == 0) {
                    candidate = (byte) (Access.isCasing(mapper.getBlockStateFromBlockId(blockId)) ? 2 : 1);
                    this.candidates.put(blockId, candidate);
                }
                if (candidate == 1) continue;
                BlockState state = mapper.getBlockStateFromBlockId(blockId);
                pos.set(i & 31, i >> 10, (i >> 5) & 31);
                var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
                ModelData data = model.getModelData(view, pos, state, ModelData.EMPTY);
                int[] indices = Access.indices(data);
                if (indices == null || Arrays.stream(indices).allMatch(index -> index == -1)) continue;
                // Only final face indices form the key: unrelated neighbour states must not
                // multiply model entries. The ordinary voxel store remains untouched.
                String key = Arrays.toString(indices);
                int variant = this.variants.computeIfAbsent(new TextureKey(state, key), ignored -> {
                    CompoundTag descriptor = new CompoundTag();
                    descriptor.putIntArray("indices", indices);
                    return mapper.getIdForBlockStateVariant(state, VARIANT_TYPE, key, descriptor);
                });
                if (result == raw) result = raw.clone();
                result[i] = Mapper.withBlockBiome(raw[i], variant, Mapper.getBiomeId(raw[i]));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            disable(e);
            return raw;
        }
        return result;
    }

    public static ModelData modelData(Mapper mapper, int blockId, ModelData original) {
        if (!enabled()) return original;
        var entry = mapper.stateEntryForRenderId(blockId);
        if (!VARIANT_TYPE.equals(entry.variantType) || entry.variantData == null) return original;
        int[] indices = entry.variantData.getIntArray("indices");
        if (indices.length != 6) return original;
        try {
            return Access.withIndices(original, indices);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            disable(e);
            return original;
        }
    }

    private static synchronized void disable(Throwable error) {
        if (!failed) Logger.warn("Create connected LOD textures unavailable; using base textures", error);
        failed = true;
    }

    // CTModel's property and data are private in Create 6. Keep this optional bridge isolated
    // and fail back to the original model if a future Create release changes its layout.
    private static final class Access {
        private static final ModelProperty<Object> PROPERTY;
        private static final Constructor<?> CONSTRUCTOR;
        private static final Field INDICES;

        static {
            try {
                Field property = CTModel.class.getDeclaredField("CT_PROPERTY");
                property.setAccessible(true);
                @SuppressWarnings("unchecked")
                var typed = (ModelProperty<Object>) property.get(null);
                PROPERTY = typed;
                Class<?> type = Class.forName(CTModel.class.getName() + "$CTData");
                CONSTRUCTOR = type.getDeclaredConstructor();
                CONSTRUCTOR.setAccessible(true);
                INDICES = type.getDeclaredField("indices");
                INDICES.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        static boolean isCasing(BlockState state) {
            return CreateClient.CASING_CONNECTIVITY.get(state) != null;
        }

        static int[] indices(ModelData data) throws IllegalAccessException {
            Object ct = data.get(PROPERTY);
            return ct == null ? null : ((int[]) INDICES.get(ct)).clone();
        }

        static ModelData withIndices(ModelData original, int[] indices) throws ReflectiveOperationException {
            Object ct = CONSTRUCTOR.newInstance();
            System.arraycopy(indices, 0, (int[]) INDICES.get(ct), 0, 6);
            return original.derive().with(PROPERTY, ct).build();
        }
    }
}
