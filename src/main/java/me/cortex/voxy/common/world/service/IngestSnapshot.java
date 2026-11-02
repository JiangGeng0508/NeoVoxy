package me.cortex.voxy.common.world.service;

import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.state.BlockState;
import java.util.function.ToIntFunction;

/** Private palette storage and light arrays, stable until conversion has finished. */
record IngestSnapshot(LevelChunkSection section, DataLayer blockLight, DataLayer skyLight, long arrayBytes) {
    static IngestSnapshot capture(LevelChunkSection source, DataLayer blockLight, DataLayer skyLight) {
        var states = source.getStates().copy();
        var sourceBiomes = source.getBiomes();
        PalettedContainer<Holder<Biome>> biomes;
        if (sourceBiomes instanceof PalettedContainer<Holder<Biome>> container) biomes = container.copy();
        else {
            biomes = sourceBiomes.recreate();
            for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                biomes.set(x, y, z, sourceBiomes.get(x, y, z));
            }
        }
        var bl = blockLight == null ? null : blockLight.copy();
        var sl = skyLight == null ? null : skyLight.copy();
        long bytes = (states.data.storage().getRaw().length + biomes.data.storage().getRaw().length) * Long.BYTES;
        bytes += lightBytes(bl) + lightBytes(sl);
        return new IngestSnapshot(new LevelChunkSection(states, biomes), bl, sl, bytes);
    }

    private static long lightBytes(DataLayer light) {
        // getData() would expand a homogeneous layer just to count it.
        return light == null || light.isDefinitelyHomogenous() ? 0 : light.getData().length;
    }

    static long compatibilityEstimate(int blockEntityReferences, LittleTilesCompat.SectionSnapshot littleTiles) {
        // Logical estimate, not measured JVM object size: 8 B/reference, 32 B/cell, 24 B/material.
        // The live chunk/entity graphs and palette implementation objects are deliberately excluded.
        return blockEntityReferences * 8L + (littleTiles == null ? 0
                : littleTiles.holders().length * Long.BYTES + littleTiles.cells().size() * 40L
                    + littleTiles.materials().size() * 32L);
    }

    static int activeCompatibilityMask(LevelChunkSection section, ToIntFunction<BlockState> classify) {
        int[] mask = {0};
        // maybeHas() includes unused palette entries left behind after a holder was removed.
        section.getStates().count((state, count) -> { if (count != 0) mask[0] |= classify.applyAsInt(state); });
        return mask[0];
    }
}
