package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceExtractorTest {
    private static final SurfaceExtractor.Rules RULES = new SurfaceExtractor.Rules() {
        public boolean invisible(BlockState state) { return state.isAir() || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.GLASS); }
        public boolean overlay(BlockState state) { return state.is(Blocks.WATER) || state.is(Blocks.RED_STAINED_GLASS); }
        public int opacity(BlockState state) { return state.is(Blocks.WATER) ? 1 : 0; }
    };

    @Test void lodZeroFindsSurfaceAcrossNegativeSectionBoundariesAndNeverRequestsNetwork() {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        var engine = new WorldEngine(storage);
        AtomicInteger requests = new AtomicInteger();
        storage.setMissListener(key -> requests.incrementAndGet());
        try {
            int stone = engine.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            for (int sy = -2; sy < 2; sy++) {
                var section = WorldSection._createRawUntrackedUnsafeSection(0, -17, sy, -1);
                section.setKnownOctants(255);
                section.setUniform(Mapper.AIR);
                if (sy == -1) {
                    for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++)
                        section.materialize()[WorldSection.getIndex(x, 31, z)] = Mapper.composeMappingId((byte) 15, stone, 0);
                }
                storage.saveSection(section);
            }
            var tile = SurfaceExtractor.extract(engine, -33, -1, -64, 64, true, RULES, () -> false);
            assertEquals(-33, tile.chunkX());
            for (var pixel : tile.pixels()) {
                assertNotNull(pixel);
                assertEquals(-1, pixel.height());
                assertEquals(Blocks.STONE.defaultBlockState(), pixel.state());
                assertFalse(pixel.provisional());
                assertEquals(0, pixel.lod());
            }
            assertEquals(0, requests.get(), "local complement reads must never request fine LOD from server");
            assertEquals(0, engine.getActiveSectionCount());
        } finally { engine.free(); }
    }

    @Test void unknownSkyIsProvisionalAndLaterKnownAirAndHigherRoofCorrectIt() {
        var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        try {
            Mapper mapper = engine.getMapper();
            int stone = mapper.getIdForBlockState(Blocks.STONE.defaultBlockState());
            long[] grid = new long[64 * 256];
            byte[] detail = new byte[grid.length];
            Arrays.fill(detail, (byte) -1);
            grid[20 * 256] = Mapper.composeMappingId((byte) 15, stone, 0);
            detail[20 * 256] = 0;
            var first = SurfaceExtractor.project(0, 0, 0, 64, grid, detail, mapper, RULES).pixels()[0];
            assertEquals(20, first.height());
            assertTrue(first.provisional());
            assertNull(SurfaceExtractor.project(0, 0, 0, 64, grid, detail, mapper, RULES).pixels()[1]);
            Arrays.fill(detail, (byte) 0);
            grid[45 * 256] = grid[20 * 256];
            var corrected = SurfaceExtractor.project(0, 0, 0, 64, grid, detail, mapper, RULES).pixels()[0];
            assertEquals(45, corrected.height());
            assertFalse(corrected.provisional());
            assertTrue(SurfaceProvenance.mayReplace(SurfaceProvenance.encode(1, true), 0, false));
        } finally { engine.free(); }
    }

    @Test void waterAndTransparentCoverRetainBottomTopBiomeAndOpacity() {
        var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        try {
            var mapper = engine.getMapper();
            int biome = mapper.getOrCreateBiomeId("minecraft:swamp");
            long[] grid = new long[32 * 256];
            byte[] detail = new byte[grid.length];
            for (int y = 4; y <= 10; y++) {
                var state = y == 4 ? Blocks.SAND.defaultBlockState() : Blocks.WATER.defaultBlockState();
                grid[y * 256] = Mapper.composeMappingId((byte) 15, mapper.getIdForBlockState(state), biome);
            }
            grid[12 * 256] = Mapper.composeMappingId((byte) 15, mapper.getIdForBlockState(Blocks.SHORT_GRASS.defaultBlockState()), biome);
            var surface = SurfaceExtractor.project(0, 0, 0, 32, grid, detail, mapper, RULES).pixels()[0];
            assertEquals(4, surface.height());
            assertEquals(10, surface.top());
            assertEquals("minecraft:swamp", surface.biome().location().toString());
            assertEquals(1, surface.covers().size());
            assertEquals(6, surface.covers().getFirst().opacity());
            assertFalse(surface.provisional());
        } finally { engine.free(); }
    }

    @Test void coarseDataFillsMissingFineDataButKnownFineAirErasesIt() {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        var engine = new WorldEngine(storage);
        try {
            int stone = engine.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            var coarse = WorldSection._createRawUntrackedUnsafeSection(4, -1, 0, -1);
            coarse.setUniform(Mapper.AIR);
            coarse.materialize()[WorldSection.getIndex(31, 3, 31)] = Mapper.composeMappingId((byte) 15, stone, 0);
            storage.saveSection(coarse);
            var first = SurfaceExtractor.extract(engine, -1, -1, 0, 96, true, RULES, () -> false).pixels()[0];
            assertEquals(63, first.height());
            assertEquals(4, first.lod());
            assertTrue(first.provisional());
            for (int sy = 0; sy < 3; sy++) {
                var fine = WorldSection._createRawUntrackedUnsafeSection(0, -1, sy, -1);
                fine.setUniform(Mapper.AIR);
                fine.setKnownOctants(255);
                storage.saveSection(fine);
            }
            var corrected = SurfaceExtractor.extract(engine, -1, -1, 0, 96, true, RULES, () -> false).pixels()[0];
            assertTrue(corrected.state().isAir());
            assertEquals(0, corrected.lod());
            assertFalse(corrected.provisional());
        } finally { engine.free(); }
    }

    @Test void adjacentMapRegionsUseTheirOwnSectionsAndClipDimensionHeight() {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        var engine = new WorldEngine(storage);
        try {
            int stone = engine.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState());
            for (int sx : new int[]{-17, -16}) {
                var section = WorldSection._createRawUntrackedUnsafeSection(0, sx, -2, 0);
                section.setKnownOctants(255);
                section.setUniform(Mapper.AIR);
                int height = sx == -17 ? 8 : 12;
                for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    section.materialize()[WorldSection.getIndex(x, height, z)] = Mapper.composeMappingId((byte) 15, stone, 0);
                    // Above the fixture dimension's upper boundary: must be ignored.
                    section.materialize()[WorldSection.getIndex(x, 31, z)] = Mapper.composeMappingId((byte) 15, stone, 0);
                }
                storage.saveSection(section);
            }
            var west = SurfaceExtractor.extract(engine, -33, 0, -64, -40, false, RULES, () -> false);
            var east = SurfaceExtractor.extract(engine, -32, 0, -64, -40, false, RULES, () -> false);
            assertEquals(-56, west.pixels()[15].height());
            assertEquals(-52, east.pixels()[0].height());
            assertFalse(east.pixels()[0].provisional());
            assertNull(SurfaceExtractor.extract(engine, -33, 0, -64, 320, true, RULES, () -> true));
            assertEquals(0, engine.getActiveSectionCount());
        } finally { engine.free(); }
    }

    @Test void tintedGlassAndWaterloggedCoverUseOverlayRules() {
        var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        try {
            var mapper = engine.getMapper();
            long[] grid = new long[16 * 256];
            byte[] detail = new byte[grid.length];
            grid[2 * 256] = Mapper.composeMappingId((byte) 15, mapper.getIdForBlockState(Blocks.SAND.defaultBlockState()), 0);
            grid[4 * 256] = Mapper.composeMappingId((byte) 15, mapper.getIdForBlockState(Blocks.RED_STAINED_GLASS.defaultBlockState()), 0);
            var covered = SurfaceExtractor.project(0, 0, 0, 16, grid, detail, mapper, RULES).pixels()[0];
            assertEquals(2, covered.height());
            assertEquals(4, covered.top());
            assertTrue(covered.covers().getFirst().state().is(Blocks.RED_STAINED_GLASS));
            grid[4 * 256] = Mapper.composeMappingId((byte) 15, mapper.getIdForBlockState(Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true)), 0);
            var logged = SurfaceExtractor.project(0, 0, 0, 16, grid, detail, mapper, RULES).pixels()[0];
            assertEquals(4, logged.height());
            assertTrue(logged.state().is(Blocks.OAK_SLAB));
            assertTrue(logged.covers().getFirst().state().is(Blocks.WATER));
        } finally { engine.free(); }
    }
}
