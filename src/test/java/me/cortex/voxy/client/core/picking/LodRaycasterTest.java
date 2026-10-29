package me.cortex.voxy.client.core.picking;

import me.cortex.voxy.client.compat.pingwheel.PingWheelBridge;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LodRaycasterTest {
    @Test void distanceUsesFullLodRadiusAndOptionalBlockCap() {
        assertEquals(8192, PickingDistance.limit(16, 0));
        assertEquals(32768, PickingDistance.limit(64, 0));
        assertEquals(320, PickingDistance.limit(0.625f, 0));
        assertEquals(128, PickingDistance.limit(16, 128));
        assertEquals(8192, PickingDistance.limit(16, 32768));
        assertEquals(0, PickingDistance.limit(Float.NaN, 0));
        assertEquals(0, PickingDistance.limit(Float.POSITIVE_INFINITY, 0));
        assertEquals(0, PickingDistance.limit(0, 0));
        assertEquals(0, PickingDistance.limit(-1, 0));
    }
    private static final LodRaycaster.Bounds BOUNDS = new LodRaycaster.Bounds(-30_000_000, -64, -30_000_000, 30_000_000, 320, 30_000_000);
    private static final long TIME = 5_000_000_000L;

    @ParameterizedTest
    @CsvSource({"1, -0.01, 0.123, true", "1, -0.005, 1, true", "-1, -0.01, -0.123, true",
            "1, 0, 0.123, true", "1, -0.01, 0.123, false", "-1, -0.01, -0.123, false"})
    void shallowRaysCrossSectionBoundariesWithoutFalseBudgetFailures(double dx, double dy, double dz, boolean uniform) {
        Vec3 origin = new Vec3(0.5, 201.62, 0.5);
        int target = dx > 0 ? 8192 : -8192;
        var source = new LodRaycaster.Source() {
            int sectionX, reads;
            public void prepare(int x, int y, int z) { this.sectionX = x; this.reads++; }
            public int finestLevel() { return 0; }
            public LodRaycaster.Cell uniform() {
                if (!uniform) return null;
                boolean solid = dx > 0 ? this.sectionX * 32 >= target : (this.sectionX + 1) * 32 <= target;
                return new LodRaycaster.Cell(solid ? LodRaycaster.Material.SOLID : LodRaycaster.Material.AIR, 4);
            }
            public LodRaycaster.Cell sample(int x, int y, int z) {
                boolean solid = dx > 0 ? x >= target : x + 1 <= target;
                return new LodRaycaster.Cell(solid ? LodRaycaster.Material.SOLID : LodRaycaster.Material.AIR, 0);
            }
            public int reads() { return this.reads; }
        };
        var ray = new LodRaycaster.Ray(origin, new Vec3(dx, dy, dz), 12000, BOUNDS);
        var hit = LodRaycaster.trace(ray, source, () -> false, TIME, PingWheelBridge.MAX_NODES);
        assertEquals(LodRaycaster.Status.HIT, hit.status(), hit.toString());
        assertEquals(target, hit.position().x, 1e-8);
        assertEquals(origin.y + (target - origin.x) * dy / dx, hit.position().y, 1e-8);
        assertEquals(origin.z + (target - origin.x) * dz / dx, hit.position().z, 1e-8);
        assertEquals(dx > 0 ? Direction.WEST : Direction.EAST, hit.normal());
    }

    @ParameterizedTest
    @ValueSource(ints = {-16_777_216, 0, 16_777_216})
    void clippedShallowRaysAlsoWorkAtLargeWorldCoordinates(int offset) {
        Vec3 origin = new Vec3(offset + 0.5, 400, offset + 0.5);
        Vec3 direction = new Vec3(1, -0.015, 0.123);
        int target = offset + 8192;
        var source = new LodRaycaster.Source() {
            int sectionX;
            public void prepare(int x, int y, int z) { this.sectionX = x; }
            public int finestLevel() { return 4; }
            public LodRaycaster.Cell uniform() {
                return new LodRaycaster.Cell(this.sectionX * 32 >= target
                        ? LodRaycaster.Material.SOLID : LodRaycaster.Material.AIR, 4);
            }
            public LodRaycaster.Cell sample(int x, int y, int z) { throw new AssertionError("uniform section"); }
            public int reads() { return 0; }
        };
        var hit = LodRaycaster.trace(new LodRaycaster.Ray(origin, direction, 9000, BOUNDS),
                source, () -> false, TIME, PingWheelBridge.MAX_NODES);
        assertEquals(LodRaycaster.Status.HIT, hit.status(), hit.toString());
        assertEquals(target, hit.position().x, 1e-8);
        assertEquals(origin.y + (target - origin.x) * direction.y, hit.position().y, 1e-8);
        assertEquals(Direction.WEST, hit.normal());
    }

    @Test void almostSimultaneousCrossingsCannotSkipUnknownIntervals() {
        var source = new LodRaycaster.Source() {
            int sx, sz;
            public void prepare(int x, int y, int z) { this.sx = x; this.sz = z; }
            public int finestLevel() { return 4; }
            public LodRaycaster.Cell uniform() {
                return new LodRaycaster.Cell(this.sx != this.sz ? LodRaycaster.Material.UNKNOWN
                        : this.sx > 0 ? LodRaycaster.Material.SOLID : LodRaycaster.Material.AIR, 4);
            }
            public LodRaycaster.Cell sample(int x, int y, int z) { throw new AssertionError("uniform section"); }
            public int reads() { return 0; }
        };
        for (double offset : new double[]{-1e-8, 0, 1e-8}) {
            var hit = LodRaycaster.trace(new LodRaycaster.Ray(new Vec3(0.5, 8, 0.5 + offset),
                    new Vec3(1, 0, 1), 100, BOUNDS), source, () -> false, TIME, PingWheelBridge.MAX_NODES);
            assertEquals(offset == 0 ? LodRaycaster.Status.HIT : LodRaycaster.Status.INCOMPLETE, hit.status(), hit.toString());
            if (offset != 0) assertNull(hit.position());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final SectionSerializationStorage storage = new SectionSerializationStorage(new MemoryStorageBackend());
        final WorldEngine engine = new WorldEngine(this.storage);
        final AtomicInteger remote = new AtomicInteger();
        Fixture() { this.storage.setMissListener(key -> this.remote.incrementAndGet()); }
        WorldSection section(int lod, int x, int y, int z, BlockState state, int known) {
            var s = WorldSection._createRawUntrackedUnsafeSection(lod, x, y, z);
            s.setUniform(value(state));
            s.setKnownOctants(known);
            return s;
        }
        long value(BlockState state) { return Mapper.composeMappingId((byte) 15, this.engine.getMapper().getIdForBlockState(state), 0); }
        LodRaycaster.Result trace(Vec3 origin, Vec3 direction, double distance, boolean crouch) {
            return trace(origin, direction, distance, crouch, PingWheelBridge.MAX_SECTION_READS);
        }
        LodRaycaster.Result trace(Vec3 origin, Vec3 direction, double distance, boolean crouch, int maxReads) {
            this.engine.acquireRef();
            try (var source = new LocalLodSource(this.engine, crouch, () -> false, System.nanoTime() + TIME, maxReads)) {
                return LodRaycaster.trace(new LodRaycaster.Ray(origin, direction, distance, BOUNDS), source, () -> false, TIME, PingWheelBridge.MAX_NODES);
            } finally { this.engine.releaseRef(); }
        }
        @Override public void close() {
            assertEquals(0, this.remote.get(), "picking must not trigger remote LOD requests");
            assertEquals(0, this.engine.getActiveSectionCount(), "every section reference must be released");
            this.engine.free();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2048, 8192, 32752})
    void distantCoarseTerrainCanBePickedAcrossTheFullRenderRadius(int target) {
        try (var f = new Fixture()) {
            for (int sx = 0; sx <= target / 512; sx++) {
                var coarse = f.section(4, sx, 0, 0, Blocks.AIR.defaultBlockState(), 255);
                if (sx == target / 512)
                    coarse.materialize()[WorldSection.getIndex((target >> 4) & 31, 0, 0)] = f.value(Blocks.STONE.defaultBlockState());
                f.storage.saveSection(coarse);
            }
            Vec3 origin = new Vec3(0.5, 8, 8), direction = new Vec3(1, 0, 0);
            double distance = PickingDistance.limit(64, 0);
            var hit = f.trace(origin, direction, distance, false);
            assertEquals(LodRaycaster.Status.HIT, hit.status(), hit.toString());
            assertEquals(target, hit.position().x, 1e-9);
            assertEquals(4, hit.lod());
            assertEquals(Direction.WEST, hit.normal());
            assertTrue(hit.visitedNodes() < 4 * (target / 32 + 1) + 256,
                    "known uniform coarse air should skip voxel traversal");
            assertEquals(LodRaycaster.Status.MISS,
                    f.trace(origin, direction, PickingDistance.limit(64, 1024), false).status());
            if (target > 8192) {
                assertTrue(hit.sectionReads() > 256, "exercise the old implicit read-budget limit");
                assertEquals(LodRaycaster.Status.BUDGET, f.trace(origin, direction, distance, false, 256).status());
            }
        }
    }

    @Test void localOnlyTerrainFindsExactEntryAfterKnownUniformAir() {
        try (var f = new Fixture()) {
            f.storage.saveSection(f.section(0, 0, 0, 0, Blocks.AIR.defaultBlockState(), 255));
            f.storage.saveSection(f.section(0, 1, 0, 0, Blocks.STONE.defaultBlockState(), 255));
            var hit = f.trace(new Vec3(0.5, 8, 8), new Vec3(1, 0, 0), 100, false);
            assertEquals(LodRaycaster.Status.HIT, hit.status());
            assertEquals(32, hit.position().x, 1e-9);
            assertEquals(Direction.WEST, hit.normal());
            assertEquals(0, hit.lod());
            assertTrue(hit.sectionReads() <= 10);
            assertTrue(hit.visitedNodes() < 10, "uniform sections skip per-voxel traversal");
        }
    }

    @Test void shallowRayHitsLocallyStoredCoarseTerrainWithoutRemoteRequests() {
        try (var f = new Fixture()) {
            for (int sx = 0; sx <= 16; sx++) for (int sz = 0; sz <= 2; sz++)
                f.storage.saveSection(f.section(4, sx, 0, sz,
                        (sx == 16 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 255));
            var hit = f.trace(new Vec3(0.5, 201.62, 0.5), new Vec3(1, -0.01, 0.123), 9000, false);
            assertEquals(LodRaycaster.Status.HIT, hit.status(), hit.toString());
            assertEquals(8192, hit.position().x, 1e-8);
            assertEquals(4, hit.lod());
            assertEquals(Direction.WEST, hit.normal());
        }
    }

    @Test void unknownAndLegacyAirNeverExposeTerrainBehindThem() {
        try (var f = new Fixture()) {
            f.storage.saveSection(f.section(0, 1, 0, 0, Blocks.STONE.defaultBlockState(), 255));
            assertEquals(LodRaycaster.Status.INCOMPLETE, f.trace(new Vec3(1, 8, 8), new Vec3(1, 0, 0), 100, false).status());
            f.storage.saveSection(f.section(0, 0, 0, 0, Blocks.AIR.defaultBlockState(), 0));
            assertEquals(LodRaycaster.Status.INCOMPLETE, f.trace(new Vec3(1, 8, 8), new Vec3(1, 0, 0), 100, false).status());
        }
    }

    @Test void coarseSurfaceIsApproximateAndFineKnownAirOverridesIt() {
        try (var f = new Fixture()) {
            var coarse = f.section(4, 0, 0, 0, Blocks.AIR.defaultBlockState(), 255);
            coarse.materialize()[WorldSection.getIndex(2, 0, 0)] = f.value(Blocks.STONE.defaultBlockState());
            f.storage.saveSection(coarse);
            var hit = f.trace(new Vec3(0.5, 8, 8), new Vec3(1, 0, 0), 63, false);
            assertEquals(LodRaycaster.Status.HIT, hit.status());
            assertEquals(32, hit.position().x, 1e-9);
            assertEquals(4, hit.lod());
            f.storage.saveSection(f.section(0, 1, 0, 0, Blocks.AIR.defaultBlockState(), 255));
            assertEquals(LodRaycaster.Status.MISS, f.trace(new Vec3(0.5, 8, 8), new Vec3(1, 0, 0), 63, false).status());
        }
    }

    @Test void partiallyKnownFineAirOnlyOverridesItsOwnOctant() {
        try (var f = new Fixture()) {
            f.storage.saveSection(f.section(4, 0, 0, 0, Blocks.STONE.defaultBlockState(), 255));
            // West/bottom/north octant is known empty; east remains unknown and may use LOD4.
            f.storage.saveSection(f.section(0, 0, 0, 0, Blocks.AIR.defaultBlockState(), 1));
            var hit = f.trace(new Vec3(0.5, 8, 8), new Vec3(1, 0, 0), 30, false);
            assertEquals(LodRaycaster.Status.HIT, hit.status());
            assertEquals(16, hit.position().x, 1e-9);
            assertEquals(4, hit.lod());
        }
    }

    @Test void negativeBoundaryAndSimultaneousCornerCrossingHaveSingleOwners() {
        try (var f = new Fixture()) {
            f.storage.saveSection(f.section(0, -1, 0, -1, Blocks.AIR.defaultBlockState(), 255));
            var stone = f.section(0, -2, 0, -2, Blocks.STONE.defaultBlockState(), 255);
            f.storage.saveSection(stone);
            var diagonal = f.trace(new Vec3(-1, 8, -1), new Vec3(-1, 0, -1), 100, false);
            assertEquals(LodRaycaster.Status.HIT, diagonal.status());
            assertEquals(-32, diagonal.position().x, 1e-8);
            assertEquals(-32, diagonal.position().z, 1e-8);
            f.storage.saveSection(f.section(0, -1, 0, 0, Blocks.STONE.defaultBlockState(), 255));
            var boundary = f.trace(new Vec3(0, 8, 0), new Vec3(-1, 0, 0), 16, false);
            assertEquals(LodRaycaster.Status.HIT, boundary.status());
            assertEquals(Vec3.ZERO.x, boundary.position().x);
            assertEquals(Direction.EAST, boundary.normal());
        }
    }

    @Test void axisBoundaryInsideVoxelAndDimensionHeightAreHandled() {
        try (var f = new Fixture()) {
            var section = f.section(0, 0, -2, 0, Blocks.AIR.defaultBlockState(), 255);
            section.materialize()[WorldSection.getIndex(0, 1, 0)] = f.value(Blocks.STONE.defaultBlockState());
            f.storage.saveSection(section);
            var hit = f.trace(new Vec3(0, -60, 0), new Vec3(0, -1, 0), 20, false);
            assertEquals(LodRaycaster.Status.HIT, hit.status());
            assertEquals(-62, hit.position().y, 1e-9);
            assertEquals(Direction.UP, hit.normal());
            var inside = f.trace(new Vec3(0.5, -62.5, 0.5), new Vec3(0, 1, 0), 1, false);
            assertEquals(LodRaycaster.Status.HIT, inside.status());
            assertEquals(-62.5, inside.position().y);
            assertEquals(LodRaycaster.Status.MISS, f.trace(new Vec3(0, 321, 0), new Vec3(0, 1, 0), 100, false).status());
        }
    }

    @Test void fluidAndTransparentShapesRespectCrouchingApproximation() {
        try (var f = new Fixture()) {
            var section = f.section(0, 0, 0, 0, Blocks.AIR.defaultBlockState(), 255);
            section.materialize()[WorldSection.getIndex(4, 8, 8)] = f.value(Blocks.WATER.defaultBlockState());
            section.materialize()[WorldSection.getIndex(8, 8, 8)] = f.value(Blocks.GLASS.defaultBlockState());
            section.materialize()[WorldSection.getIndex(12, 8, 8)] = f.value(Blocks.STONE.defaultBlockState());
            f.storage.saveSection(section);
            var origin = new Vec3(0.5, 8.5, 8.5);
            var visual = f.trace(origin, new Vec3(1, 0, 0), 16, false);
            assertEquals(LodRaycaster.Status.HIT, visual.status(), visual.toString());
            assertEquals(12, visual.position().x, 1e-9);
            assertEquals(4, f.trace(origin, new Vec3(1, 0, 0), 16, true).position().x, 1e-9);
            assertEquals(8, f.trace(new Vec3(6.5, 8.5, 8.5), new Vec3(1, 0, 0), 9, true).position().x, 1e-9);
        }
    }

    @Test void distanceAndReadBudgetDoNotReturnPartialHits() {
        try (var f = new Fixture()) {
            f.storage.saveSection(f.section(0, 0, 0, 0, Blocks.AIR.defaultBlockState(), 255));
            f.storage.saveSection(f.section(0, 1, 0, 0, Blocks.STONE.defaultBlockState(), 255));
            assertEquals(LodRaycaster.Status.MISS, f.trace(new Vec3(0.5, 8, 8), new Vec3(1, 0, 0), 20, false).status());
            var limited = f.trace(new Vec3(0.5, 8, 8), new Vec3(1, 0, 0), 100, false, 1);
            assertEquals(LodRaycaster.Status.BUDGET, limited.status());
            assertNull(limited.position());
        }
    }

    @Test void invalidCancellationAndNodeBudgetStopBeforeSendingAnyCoordinate() {
        var source = new LodRaycaster.Source() {
            public void prepare(int x, int y, int z) {}
            public int finestLevel() { return 0; }
            public LodRaycaster.Cell sample(int x, int y, int z) { return new LodRaycaster.Cell(LodRaycaster.Material.AIR, 0); }
            public int reads() { return 0; }
        };
        var ray = new LodRaycaster.Ray(new Vec3(1, 8, 8), new Vec3(1, 0, 0), 100, BOUNDS);
        assertEquals(LodRaycaster.Status.CANCELLED, LodRaycaster.trace(ray, source, () -> true, TIME, 100).status());
        assertEquals(LodRaycaster.Status.BUDGET, LodRaycaster.trace(ray, source, () -> false, TIME, 1).status());
        assertEquals(LodRaycaster.Status.INVALID, LodRaycaster.trace(new LodRaycaster.Ray(Vec3.ZERO, Vec3.ZERO, 100, BOUNDS), source, () -> false, TIME, 100).status());
        assertEquals(LodRaycaster.Status.INVALID, LodRaycaster.trace(new LodRaycaster.Ray(new Vec3(Double.NaN, 0, 0), new Vec3(1, 0, 0), 100, BOUNDS), source, () -> false, TIME, 100).status());
    }
}
