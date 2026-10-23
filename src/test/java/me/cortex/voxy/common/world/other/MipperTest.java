package me.cortex.voxy.common.world.other;

import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Regression cases adapted from neo-voxy-multiversion's TerrainMipperVerification. */
class MipperTest {
    private static final long AIR = Mapper.airWithLight(0x0F);
    private MemoryStorageBackend storage;
    private Mapper mapper;
    private long stone, grass, water;

    @BeforeEach
    void createMapper() {
        this.storage = new MemoryStorageBackend();
        this.mapper = new Mapper(this.storage);
        this.stone = Mapper.composeMappingId((byte) 0x0F,
                this.mapper.getIdForBlockState(Blocks.STONE.defaultBlockState()), 2);
        this.grass = Mapper.composeMappingId((byte) 0x0F,
                this.mapper.getIdForBlockState(Blocks.GRASS_BLOCK.defaultBlockState()), 3);
        this.water = Mapper.composeMappingId((byte) 0x0F,
                this.mapper.getIdForBlockState(Blocks.WATER.defaultBlockState()), 4);
    }

    @AfterEach
    void closeMapper() {
        this.mapper.close();
        this.storage.close();
    }

    @Test
    void preservesEveryHalfOccupiedArrangementWithoutFillingMinoritySolids() {
        for (int mask = 0; mask < 256; mask++) {
            long[] children = new long[8];
            for (int i = 0; i < children.length; i++) {
                children[i] = (mask & (1 << i)) != 0 ? this.stone : AIR;
            }
            long parent = mip(children);
            assertEquals(Integer.bitCount(mask) >= 4,
                    !Mapper.isAir(parent) || Mapper.isSurfaceCarrier(parent), "occupancy mask=" + mask);
        }
    }

    @Test
    void retainsOneBlockWallsAndRoofsThroughAllFourMipLevels() {
        for (int axis = 0; axis < 3; axis++) {
            for (int position = 0; position < 16; position++) {
                var section = VoxelizedSection.createEmpty();
                Arrays.fill(section.section, AIR);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if ((axis == 0 ? x : axis == 1 ? y : z) == position) {
                        section.section[y * 256 + z * 16 + x] = this.stone;
                    }
                }
                WorldVoxilizedSectionMipper.mipSection(section, this.mapper);
                for (int level = 1; level <= 4; level++) {
                    int size = 16 >> level;
                    int occupied = 0;
                    for (int y = 0; y < size; y++) for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                        long voxel = section.get(level, x, y, z);
                        if (!Mapper.isAir(voxel) || Mapper.isSurfaceCarrier(voxel)) occupied++;
                    }
                    assertEquals(size * size, occupied,
                            "thin plane axis=" + axis + ", pos=" + position + ", level=" + level);
                }
            }
        }
    }

    @Test
    void preservesGroundHeightAndSurfaceMaterialWhenWritingMips() throws ReflectiveOperationException {
        var insert = WorldUpdater.class.getDeclaredMethod("insertSectionLvlIntoWorld",
                WorldEngine.class, VoxelizedSection.class, WorldSection.class);
        insert.setAccessible(true);
        // Check the actual write: unresolved boundary surfaces must land on the supporting
        // voxel below, rather than raising the terrain or persisting a transient carrier.
        for (int height = 1; height < 16; height++) {
            var section = VoxelizedSection.createEmpty();
            Arrays.fill(section.section, AIR);
            for (int y = 0; y < height; y++) {
                Arrays.fill(section.section, y * 256, (y + 1) * 256,
                        y == height - 1 ? this.grass : this.stone);
            }
            WorldVoxilizedSectionMipper.mipSection(section, this.mapper);
            for (int sectionY : new int[]{1, -1, 1201}) {
                section.setPosition(0, sectionY, 0);
                for (int level = 1; level <= 4; level++) {
                    int scale = 1 << level;
                    int size = 16 >> level;
                    var target = WorldSection._createRawUntrackedUnsafeSection(
                            level, 0, sectionY >> (level + 1), 0);
                    int baseY = (sectionY & ((1 << (level + 1)) - 1)) << (4 - level);
                    long[] data = target.materialize();
                    Arrays.fill(data, AIR);
                    Arrays.fill(data, 0, baseY * 1024, this.stone);
                    insert.invoke(null, null, section, target);

                    int top = baseY;
                    for (int y = baseY; y < baseY + size; y++) {
                        if (!Mapper.isAir(target.get(WorldSection.getIndex(0, y, 0)))) top = y + 1;
                    }
                    String context = "height=" + height + ", sectionY=" + sectionY + ", level=" + level;
                    assertEquals(sectionY * 16 + (height / scale) * scale,
                            (target.y * 32 + top) * scale, context);
                    long surface = target.get(WorldSection.getIndex(0, top - 1, 0));
                    assertEquals(Mapper.getBlockId(this.grass), Mapper.getBlockId(surface), context);
                    assertEquals(Mapper.getBiomeId(this.grass), Mapper.getBiomeId(surface), context);
                    for (int y = 0; y < baseY + size; y++) {
                        assertFalse(Mapper.isSurfaceCarrier(target.get(WorldSection.getIndex(0, y, 0))), context);
                    }
                    var result = insert.invoke(null, null, section, target);
                    var statusAccessor = result.getClass().getDeclaredMethod("status");
                    statusAccessor.setAccessible(true);
                    long status = (long) statusAccessor.invoke(result);
                    assertEquals(0L, status & 1, "repeated terrain write must be stable: " + context);
                }
            }
        }
    }

    @Test
    void preservesFluidRulesAndLightAcrossPendingSurfaceMips() {
        assertEquals(Mapper.getBlockId(this.water), Mapper.getBlockId(mip(
                this.water, this.water, this.water, this.water, AIR, AIR, AIR, AIR)));
        assertEquals(Mapper.getBlockId(this.stone), Mapper.getBlockId(mip(
                this.water, this.water, this.water, this.water, this.stone, this.stone, this.stone, this.stone)));
        assertTrue(Mapper.isAir(mip(this.water, AIR, AIR, AIR, AIR, AIR, AIR, AIR)));

        long litStone = Mapper.withLight(this.stone, 0xA3);
        long carrier = mip(litStone, litStone, litStone, litStone, AIR, AIR, AIR, AIR);
        assertTrue(Mapper.isSurfaceCarrier(carrier));
        assertEquals(0x5F, Mapper.getLightId(carrier));
        long parent = mip(carrier, carrier, carrier, carrier, AIR, AIR, AIR, AIR);
        assertTrue(Mapper.isSurfaceCarrier(parent));
        assertEquals(Mapper.getBlockId(this.stone), Mapper.getBlockId(Mapper.restoreSurfaceCarrier(parent)));
        assertEquals(0x2F, Mapper.getLightId(parent));
    }

    private long mip(long... children) {
        return Mipper.mip(children[0], children[1], children[2], children[3],
                children[4], children[5], children[6], children[7], this.mapper);
    }
}
