package me.cortex.voxy.common.world.service;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.IdMapper;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IngestSnapshotTest {
    static final Holder<Biome> PLAINS = Holder.Reference.createStandAlone(new HolderOwner<>() {}, Biomes.PLAINS);
    static final Holder<Biome> DESERT = Holder.Reference.createStandAlone(new HolderOwner<>() {}, Biomes.DESERT);

    static LevelChunkSection section(BlockState state) {
        var biomes = new IdMapper<Holder<Biome>>(); biomes.add(PLAINS); biomes.add(DESERT);
        return new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state,
                PalettedContainer.Strategy.SECTION_STATES),
                new PalettedContainer<>(biomes, PLAINS, PalettedContainer.Strategy.SECTION_BIOMES));
    }

    @Test void vanillaStateBiomeAndLightStorageIsIndependentOfLaterSourceWrites() {
        var source = section(Blocks.STONE.defaultBlockState());
        source.setBlockState(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        var block = new DataLayer(); block.set(0, 0, 0, 7);
        var sky = new DataLayer(15);
        var snapshot = IngestSnapshot.capture(source, block, sky);
        source.setBlockState(0, 0, 0, Blocks.DIRT.defaultBlockState());
        ((PalettedContainer<Holder<Biome>>) source.getBiomes()).set(0, 0, 0, DESERT);
        block.set(0, 0, 0, 1); sky.set(0, 0, 0, 0);
        assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), snapshot.section().getBlockState(0, 0, 0));
        assertSame(PLAINS, snapshot.section().getBiomes().get(0, 0, 0));
        assertEquals(7, snapshot.blockLight().get(0, 0, 0)); assertEquals(15, snapshot.skyLight().get(0, 0, 0));
        assertEquals(4096, snapshot.arrayBytes(), "2048 B packed states plus 2048 B block light; uniform biome/sky stay compact");
    }

    @Test void homogeneousInputsKeepZeroLengthBitStorageAndDoNotAllocateLightArraysForAccounting() {
        var source = section(Blocks.AIR.defaultBlockState());
        var snapshot = IngestSnapshot.capture(source, null, new DataLayer(15));
        assertTrue(snapshot.section().hasOnlyAir()); assertEquals(0, snapshot.arrayBytes());
        assertTrue(snapshot.skyLight().isDefinitelyHomogenous());
        assertEquals(0, source.getStates().data.storage().getRaw().length);
        assertNotSame(source, snapshot.section()); assertNotSame(source.getStates(), snapshot.section().getStates());
    }

    @Test void removedHolderEntriesLeftInThePaletteCannotInheritOldCompatibilityPayloads() {
        var source = section(Blocks.STONE.defaultBlockState());
        source.setBlockState(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        assertEquals(4, IngestSnapshot.activeCompatibilityMask(source, state -> state.is(Blocks.GOLD_BLOCK) ? 4 : 0));
        source.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        assertTrue(source.maybeHas(state -> state.is(Blocks.GOLD_BLOCK)), "palette still remembers the removed value");
        var snapshot = IngestSnapshot.capture(source, null, null);
        assertEquals(0, IngestSnapshot.activeCompatibilityMask(snapshot.section(), state -> state.is(Blocks.GOLD_BLOCK) ? 4 : 0));
    }
}
