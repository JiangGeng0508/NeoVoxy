package me.cortex.voxy.client;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LodSectionRequestsTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void aCachedMissSurvivesHandshakeAndDoesNotNeedAnotherStorageRead() {
        var requests = new LodSectionRequests();
        var backend = new MemoryStorageBackend() {
            int reads;
            @Override public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
                this.reads++;
                return super.getSectionData(key, scratch);
            }
        };
        var storage = new SectionSerializationStorage(backend);
        storage.setMissListener(key -> requests.request(OVERWORLD, key));
        try {
            assertEquals(1, storage.loadSection(section(1)));
            var sent = new ArrayList<LodSectionRequests.Request>();
            // No pump while hello is pending. A repeated cached read must not hit the backend.
            assertEquals(1, storage.loadSection(section(1)));
            assertEquals(1, backend.reads);
            assertEquals(1, requests.pendingCount());
            requests.pump(OVERWORLD, 0, r -> sent.add(r));
            assertEquals(List.of(new LodSectionRequests.Request(OVERWORLD, section(1).key)), sent);
        } finally {
            storage.close();
        }
    }

    @Test
    void allRequestsBeyond512AreSentAfterRepliesWithoutNewMisses() {
        var requests = new LodSectionRequests();
        var sent = new ArrayList<LodSectionRequests.Request>();
        for (int i = 0; i < 1600; i++) requests.request(OVERWORLD, i);
        for (int i = 0; i < 20; i++) requests.pump(OVERWORLD, 0, sent::add);
        assertEquals(512, sent.size());
        assertEquals(1088, requests.pendingCount());
        int completed = 0;
        while (completed < 1600) {
            int end = sent.size();
            for (; completed < end; completed++) requests.complete(sent.get(completed), true);
            requests.pump(OVERWORLD, 0, sent::add);
            assertTrue(requests.inFlightCount() <= 512);
        }
        assertEquals(1600, new HashSet<>(sent).size());
        assertEquals(0, requests.pendingCount());
        assertEquals(0, requests.inFlightCount());
    }

    @Test
    void missingReplySuppressesCachedMissesUntilPushOrNewSession() {
        var requests = new LodSectionRequests();
        var sent = new ArrayList<LodSectionRequests.Request>();
        var request = new LodSectionRequests.Request(OVERWORLD, 42);
        requests.request(OVERWORLD, 42);
        requests.pump(OVERWORLD, 0, sent::add);
        requests.complete(request, true);
        for (int tick = 0; tick < 200; tick++) {
            requests.request(OVERWORLD, 42);
            requests.pump(OVERWORLD, Long.MAX_VALUE / 2, sent::add);
        }
        assertEquals(1, sent.size(), "confirmed absence must not enter the timeout loop");
        // A non-empty server push invalidates the remote absence, even if no pull is in flight.
        requests.complete(request, false);
        requests.request(OVERWORLD, 42);
        requests.pump(OVERWORLD, 0, sent::add);
        assertEquals(2, sent.size());
    }

    @Test
    void disconnectDiscardsOldWorkersAndNewSessionCanFetchSameCachedKey() {
        var oldSession = new LodSectionRequests();
        var newSession = new LodSectionRequests();
        var storage = new SectionSerializationStorage(new MemoryStorageBackend());
        try {
            storage.setMissListener(key -> oldSession.request(OVERWORLD, key));
            storage.loadSection(section(3));
            oldSession.close();
            storage.loadSection(section(3)); // An old worker cannot revive a disconnected queue.
            assertEquals(0, oldSession.pendingCount());
            storage.setMissListener(key -> newSession.request(OVERWORLD, key));
            storage.loadSection(section(3)); // Cached local absence is not server confirmation.
            var sent = new ArrayList<LodSectionRequests.Request>();
            newSession.pump(OVERWORLD, 0, sent::add);
            assertEquals(1, sent.size());
            oldSession.pump(OVERWORLD, 0, r -> fail("old session sent a request"));
        } finally {
            storage.close();
        }
    }

    @Test
    void failuresAndTimeoutsRetryWithDelayAndPerTickBudget() {
        var requests = new LodSectionRequests();
        var sent = new ArrayList<LodSectionRequests.Request>();
        requests.request(OVERWORLD, 1);
        requests.pump(OVERWORLD, -1, r -> false);
        assertEquals(0, requests.inFlightCount());
        assertEquals(1, requests.pendingCount());
        requests.pump(OVERWORLD, -1 + LodSectionRequests.RETRY_NANOS - 1, sent::add);
        assertTrue(sent.isEmpty());
        long sentAt = -1 + LodSectionRequests.RETRY_NANOS;
        requests.pump(OVERWORLD, sentAt, sent::add);
        requests.pump(OVERWORLD, sentAt + LodSectionRequests.TIMEOUT_NANOS - 1, sent::add);
        assertEquals(1, sent.size());
        requests.pump(OVERWORLD, sentAt + LodSectionRequests.TIMEOUT_NANOS, sent::add);
        assertEquals(2, sent.size());
        for (int i = 2; i < 200; i++) requests.request(OVERWORLD, i);
        requests.pump(OVERWORLD, sentAt + LodSectionRequests.TIMEOUT_NANOS, sent::add);
        assertEquals(2 + LodSectionRequests.SENDS_PER_TICK, sent.size());
    }

    @Test
    void dimensionsAndDeferredPayloadsKeepIndependentOwnership() {
        var requests = new LodSectionRequests();
        var sent = new ArrayList<LodSectionRequests.Request>();
        requests.request(OVERWORLD, 5);
        requests.request(NETHER, 5);
        requests.pump(OVERWORLD, 0, sent::add);
        assertEquals(List.of(new LodSectionRequests.Request(OVERWORLD, 5)), sent);
        var overworldRequest = sent.getFirst();
        requests.hold(overworldRequest);
        requests.request(OVERWORLD, 5);
        requests.pump(NETHER, LodSectionRequests.TIMEOUT_NANOS, sent::add);
        assertEquals(NETHER, sent.getLast().dimension());
        requests.pump(OVERWORLD, 2 * LodSectionRequests.TIMEOUT_NANOS, sent::add);
        assertEquals(2, sent.size(), "data waiting for mappings must not be requested again");
        requests.retry(overworldRequest, 2 * LodSectionRequests.TIMEOUT_NANOS);
        requests.pump(OVERWORLD, 2 * LodSectionRequests.TIMEOUT_NANOS + LodSectionRequests.RETRY_NANOS, sent::add);
        assertEquals(overworldRequest, sent.getLast());
        assertEquals(3, sent.size());
    }

    @Test
    void concurrentMissesAreCoalescedBeforeTheyReachTheMainThread() throws Exception {
        var requests = new LodSectionRequests();
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                workers.submit(() -> {
                    for (int key = 0; key < 1000; key++) requests.request(OVERWORLD, key);
                });
            }
            workers.shutdown();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1000, requests.pendingCount());
        var sent = new ArrayList<LodSectionRequests.Request>();
        requests.pump(OVERWORLD, 0, sent::add);
        assertEquals(LodSectionRequests.SENDS_PER_TICK, new HashSet<>(sent).size());
    }

    private static WorldSection section(int x) {
        return WorldSection._createRawUntrackedUnsafeSection(0, x, 2, -3);
    }
}
