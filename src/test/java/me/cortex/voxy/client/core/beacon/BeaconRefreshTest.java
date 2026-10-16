package me.cortex.voxy.client.core.beacon;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.BeaconIndex;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static me.cortex.voxy.testing.Reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class BeaconRefreshTest {
    private final DistantBeaconRenderer renderer = new DistantBeaconRenderer();
    private WorldEngine engine;

    @BeforeEach
    void bind() throws Exception {
        engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        set(renderer, "boundEngine", engine);
        BeaconBeamTracker.bind(engine);
    }

    @AfterEach
    void cleanup() throws Exception {
        renderer.onLogout(null);
        engine.free();
        clearStatic(DistantBeaconRenderer.class, "active");
    }

    @Test
    void movementAndRangeChangesPrepareBeamsBeforeTheyEnterDrawRange() throws Exception {
        long pos = addBeacon(160);
        drain();
        filter(0.5, 64, 1000);
        assertTrue(drain().isEmpty());
        filter(32.5, 64, 1099); // Movement is throttled to at most once per 100ms.
        assertTrue(drain().isEmpty());
        filter(32.5, 64, 1100);
        assertTrue(drain().contains(pos), "prepare at 128 blocks while draw range is only 64");
        filter(0.5, 128, 1101);
        assertTrue(drain().contains(pos), "range changes bypass the movement throttle");
    }

    @Test
    void cachedVerdictsSurviveTheRetentionBandAndExpireBeyondIt() throws Exception {
        long pos = addBeacon(160);
        drain();
        apply(pos, 1000, BeaconBeamSolver.Result.EMPTY, () -> fail("empty results do not bake"));
        filter(0.5, 64, 1000);
        assertTrue(states().containsKey(pos)); // Outside prepare=128, within retain=192.
        assertTrue(drain().isEmpty());
        filter(-40.5, 64, 1100);
        assertFalse(states().containsKey(pos));
        filter(0.5, 64, 1200);
        assertTrue(drain().isEmpty(), "an evicted beam must re-enter the preparation radius");
    }

    @Test
    void retryDeadlinesWakeTheFilterBeforeItsTwoSecondInterval() throws Exception {
        long warm = addBeacon(0);
        long lookup = addBeacon(16);
        drain();
        filter(0.5, 64, 1000);
        drain();
        apply(warm, 1000, BeaconBeamSolver.Result.DEFERRED, () -> fail());
        apply(lookup, 1000, BeaconBeamSolver.Result.RETRY, () -> fail());
        assertEquals(1500L, (long) get(renderer, "nextRetryMs"));
        filter(0.5, 64, 1499);
        assertTrue(drain().isEmpty());
        filter(0.5, 64, 1500);
        assertEquals(LongArrayList.of(warm), drain());
        assertEquals(6000L, (long) get(renderer, "nextRetryMs"));
        filter(0.5, 64, 6000);
        assertEquals(LongArrayList.of(lookup), drain());
    }

    @Test
    void unsuccessfulBakesRetryButDefinitiveEmptyResultsDoNot() throws Exception {
        long pos = addBeacon(0);
        var result = new BeaconBeamSolver.Result(List.of(new BeaconBeamSolver.Segment(0xFFFFFF, 65, 100)), false, false);
        apply(pos, 1000, result, () -> null);
        assertEquals(6000L, (long) get(states().get(pos), "retryAtMs"));
        apply(pos, 2000, result, () -> { throw new IllegalStateException("expected test bake failure"); });
        assertEquals(7000L, (long) get(states().get(pos), "retryAtMs"));
        apply(pos, 3000, BeaconBeamSolver.Result.EMPTY, () -> fail());
        assertEquals(0L, (long) get(states().get(pos), "retryAtMs"));
        renderer.onLogout(null);
        assertEquals(Long.MAX_VALUE, (long) get(renderer, "nextRetryMs"));
        assertEquals(-1L, (long) get(renderer, "lastFilterMs"));
    }

    private long addBeacon(int x) {
        engine.getBeaconIndex().setSection(x >> 4, 4, 0, new short[]{BeaconIndex.packLocal(x & 15, 0, 0)});
        return BlockPos.asLong(x, 64, 0);
    }
    private void filter(double x, double range, long now) throws Exception {
        call(renderer, "filterIfStale", x, 0.5, range, now);
    }
    private void apply(long pos, long now, BeaconBeamSolver.Result result, Supplier<DistantMesh> baker) throws Exception {
        call(renderer, "applyResult", pos, 64, now, result, baker);
    }
    private Map<Long, Object> states() throws Exception { return get(renderer, "states"); }
    private static LongArrayList drain() {
        var dirty = new LongArrayList();
        BeaconBeamTracker.drain(dirty, new LongArrayList());
        return dirty;
    }
}
