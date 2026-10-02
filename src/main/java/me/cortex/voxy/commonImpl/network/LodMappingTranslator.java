package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.world.other.Mapper;

/** Translates voxel IDs between the independent mapping tables of a server and client. */
public final class LodMappingTranslator {
    private LodMappingTranslator() {}

    /** Returns UNKNOWN_MAPPING when a required mapping has not arrived yet. */
    public static long translate(long voxel, int[] blocks, int[] biomes) {
        boolean carrier = Mapper.isSurfaceCarrier(voxel);
        long material = carrier ? Mapper.restoreSurfaceCarrier(voxel) : voxel;
        if (Mapper.isAir(material)) {
            return voxel;
        }
        int block = Mapper.getBlockId(material);
        int biome = Mapper.getBiomeId(material);
        if (blocks == null || block >= blocks.length || blocks[block] < 0
                || biomes == null || biome >= biomes.length || biomes[biome] < 0) {
            return Mapper.UNKNOWN_MAPPING;
        }
        long translated = Mapper.withBlockBiome(material, blocks[block], biomes[biome]);
        return carrier ? Mapper.makeSurfaceCarrier(translated) : translated;
    }
}
