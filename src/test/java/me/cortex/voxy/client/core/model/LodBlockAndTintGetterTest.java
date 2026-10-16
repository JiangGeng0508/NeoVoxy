package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodBlockAndTintGetterTest {
    @Test
    void readsAll26NeighborsAtEveryLodLevelAndReleasesThem() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        try {
            int stone = world.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            int water = world.getMapper().getIdForBlockState(Blocks.WATER.defaultBlockState());
            for (int level = 0; level <= WorldEngine.MAX_LOD_LAYER; level++) {
                var center = world.acquire(level, -2, 1, -3);
                try {
                    for (int y = -1; y <= 1; y++) {
                        for (int z = -1; z <= 1; z++) {
                            for (int x = -1; x <= 1; x++) {
                                if ((x | y | z) == 0) continue;
                                var neighbor = world.acquire(level, center.x + x, center.y + y, center.z + z);
                                neighbor.setUniform(Mapper.composeMappingId((byte) 0, stone, 0));
                                int index = local(x) | (local(z) << 5) | (local(y) << 10);
                                neighbor.materialize()[index] = Mapper.composeMappingId((byte) 0, water, 0);
                                world.storage.saveSection(neighbor);
                                neighbor.release();
                            }
                        }
                    }
                    // Centre queries must use the caller's substituted snapshot, not shared terrain.
                    long[] snapshot = new long[WorldSection.SECTION_VOLUME];
                    snapshot[16 | (16 << 5) | (16 << 10)] = Mapper.composeMappingId((byte) 0, water, 0);
                    try (var view = new LodBlockAndTintGetter(world, center, snapshot)) {
                        for (int y : new int[]{-1, 16, 32}) {
                            for (int z : new int[]{-1, 16, 32}) {
                                for (int x : new int[]{-1, 16, 32}) {
                                    var pos = new BlockPos(x, y, z);
                                    assertEquals(Blocks.WATER.defaultBlockState(), view.getBlockState(pos),
                                            "level=" + level + " pos=" + pos);
                                    assertEquals(Blocks.WATER.defaultBlockState().getFluidState(), view.getFluidState(pos));
                                }
                            }
                        }
                        assertTrue(view.getBlockState(new BlockPos(64, 0, 0)).isAir());
                    }
                    assertEquals(1, world.getActiveSectionCount(), "only the caller's centre reference remains");
                    assertTrue(Mapper.isAir(center.get(16 | (16 << 5) | (16 << 10))));
                } finally {
                    center.release();
                }
            }
        } finally {
            world.free();
        }
    }

    @Test
    void absentNeighborsAreAirAndAreNotCreated() {
        var world = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        var center = world.acquire(0, 0, 0, 0);
        try {
            try (var view = new LodBlockAndTintGetter(world, center, center.materialize())) {
                assertTrue(view.getBlockState(new BlockPos(-1, 32, 32)).isAir());
                assertTrue(view.getBlockState(new BlockPos(-1, 32, 32)).isAir());
            }
            assertEquals(1, world.getActiveSectionCount());
        } finally {
            center.release();
            world.free();
        }
    }

    private static int local(int sectionOffset) {
        return sectionOffset == -1 ? 31 : sectionOffset == 1 ? 0 : 16;
    }
}
