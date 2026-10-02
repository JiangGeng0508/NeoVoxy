package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodMappingTranslatorTest {
    private static final int[] BLOCKS = {0, 27};
    private static final int[] BIOMES = {4, 3};
    private static final long MATERIAL = Mapper.withBlockBiome(Mapper.airWithLight(0xA5), 1, 1);

    @Test
    void translatesSolidBlocksAndPreservesLighting() {
        long translated = LodMappingTranslator.translate(MATERIAL, BLOCKS, BIOMES);
        assertEquals(27, Mapper.getBlockId(translated));
        assertEquals(3, Mapper.getBiomeId(translated));
        assertEquals(0xA5, Mapper.getLightId(translated));
    }

    @Test
    void translatesSurfaceCarrierMaterialEvenThoughItsBlockBitsAreAir() {
        long translated = LodMappingTranslator.translate(Mapper.makeSurfaceCarrier(MATERIAL), BLOCKS, BIOMES);
        assertTrue(Mapper.isSurfaceCarrier(translated));
        assertTrue(Mapper.isAir(translated));
        long restored = Mapper.restoreSurfaceCarrier(translated);
        assertEquals(27, Mapper.getBlockId(restored));
        assertEquals(3, Mapper.getBiomeId(restored));
        assertEquals(0xA5, Mapper.getLightId(restored));
    }

    @Test
    void waitsForMissingMappingsIncludingSurfaceCarriers() {
        for (long voxel : new long[]{MATERIAL, Mapper.makeSurfaceCarrier(MATERIAL)}) {
            assertEquals(Mapper.UNKNOWN_MAPPING, LodMappingTranslator.translate(voxel, null, BIOMES));
            assertEquals(Mapper.UNKNOWN_MAPPING, LodMappingTranslator.translate(voxel, BLOCKS, null));
            assertEquals(Mapper.UNKNOWN_MAPPING, LodMappingTranslator.translate(voxel, new int[]{0}, BIOMES));
            assertEquals(Mapper.UNKNOWN_MAPPING, LodMappingTranslator.translate(voxel, BLOCKS, new int[]{0, -1}));
        }
    }

    @Test
    void ordinaryAirNeedsNoMappings() {
        long air = Mapper.airWithLight(0x7F);
        assertEquals(air, LodMappingTranslator.translate(air, null, null));
    }
}
