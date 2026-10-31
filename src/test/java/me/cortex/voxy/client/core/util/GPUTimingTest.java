package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GPUTimingTest {
    static final class Queries implements GPUTiming.QueryBackend {
        int created, reads;
        long clock;
        final Map<Integer, Long> values = new HashMap<>();
        final Set<Integer> deleted = new HashSet<>();
        boolean ready;
        public int create() { return ++created; }
        public void timestamp(int query) { values.put(query, clock += 1_000_123); }
        public boolean available(int query) { return ready; }
        public long result(int query) {
            assertTrue(ready, "A pending timestamp must never cause a blocking result read");
            reads++;
            return values.get(query);
        }
        public void delete(int query) { assertTrue(deleted.add(query), "Query deleted twice"); }
    }

    @Test void delayedQueriesRetainFrameViewportAndSessionAndAreReadExactlyOnce() {
        var queries = new Queries();
        var samples = new ArrayList<GPUTiming.Sample>();
        var timing = new GPUTiming(queries, samples::add);
        timing.begin(12, true, 2, 7, true);
        timing.marker("draw");
        timing.marker();
        timing.endViewport();
        timing.begin(12, false, 2, 7, true);
        timing.marker();
        timing.endViewport();
        timing.poll();
        timing.poll();
        assertEquals(0, queries.reads);
        assertTrue(samples.isEmpty());
        queries.ready = true;
        timing.poll();
        assertEquals(2, samples.size());
        assertEquals(12, samples.getFirst().frameId());
        assertEquals(2, samples.getFirst().captureId());
        assertEquals(7, samples.getFirst().profileId());
        assertTrue(samples.getFirst().main());
        assertFalse(samples.getLast().main());
        assertArrayEquals(new String[]{"pass0", "draw"}, samples.getFirst().labels());
        assertArrayEquals(new long[]{1_000_123, 1_000_123}, samples.getFirst().nanos());
        timing.poll();
        assertEquals(5, queries.reads);
        timing.free();
        assertEquals(queries.created, queries.deleted.size());
    }

    @Test void disabledOrSingleMarkerBatchesProduceNoSamplesAndReuseQueries() {
        var queries = new Queries();
        var samples = new ArrayList<GPUTiming.Sample>();
        var timing = new GPUTiming(queries, samples::add);
        timing.begin(1, true, 0, 0, false);
        timing.marker();
        timing.endViewport();
        assertEquals(0, queries.created);
        timing.begin(2, true, 0, 0, true);
        timing.endViewport();
        timing.poll();
        assertTrue(samples.isEmpty());
        timing.begin(3, true, 0, 0, true);
        timing.marker();
        timing.endViewport();
        queries.ready = true;
        timing.poll();
        assertEquals(1, samples.size());
        assertEquals(2, queries.created);
        timing.free();
        timing.free();
        assertEquals(queries.created, queries.deleted.size());
    }

    @Test void pendingQueriesAreBoundedAndCapacityRecoversAfterPolling() {
        var queries = new Queries();
        var samples = new ArrayList<GPUTiming.Sample>();
        var timing = new GPUTiming(queries, samples::add);
        for (int frame = 0; frame < GPUTiming.MAX_INFLIGHT + 5; frame++) {
            timing.begin(frame, true, 1, 0, true);
            timing.marker();
            timing.endViewport();
        }
        assertEquals(5, timing.droppedSamples());
        assertEquals(GPUTiming.MAX_INFLIGHT * 2, queries.created);
        queries.ready = true;
        timing.poll();
        timing.begin(100, true, 1, 0, true);
        timing.marker();
        timing.endViewport();
        timing.poll();
        assertEquals(GPUTiming.MAX_INFLIGHT + 1, samples.size());
        assertEquals(GPUTiming.MAX_INFLIGHT * 2, queries.created);
        timing.free();
    }

    @Test void markerOverflowDropsEntireBatchAndFreeReleasesPendingAndActiveQueries() {
        var queries = new Queries();
        var samples = new ArrayList<GPUTiming.Sample>();
        var timing = new GPUTiming(queries, samples::add);
        timing.begin(1, true, 0, 0, true);
        for (int i = 0; i < GPUTiming.MAX_MARKERS; i++) timing.marker();
        timing.endViewport();
        assertEquals(1, timing.droppedSamples());
        timing.begin(2, true, 0, 0, true);
        timing.marker();
        timing.endViewport(); // Pending batch.
        timing.begin(3, true, 0, 0, true); // Active batch.
        timing.free();
        assertTrue(samples.isEmpty());
        assertEquals(queries.created, queries.deleted.size());
    }
}
