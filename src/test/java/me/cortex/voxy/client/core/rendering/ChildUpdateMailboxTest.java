package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChildUpdateMailboxTest {
    private static final int CHILD = WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT;
    private static WorldSection section(int x, int mask) {
        var section = WorldSection._createRawUntrackedUnsafeSection(0, x, 0, 0);
        section._unsafeSetNonEmptyChildren((byte) mask);
        return section;
    }
    private static SectionUpdateRouter router(AtomicInteger wakeups) {
        var router = new SectionUpdateRouter();
        router.bufferChildUpdates(wakeups::incrementAndGet);
        router.setCallbacks(ignored -> {}, ignored -> {}, ignored -> fail("synchronous child callback"));
        return router;
    }

    @Test void repeatedUpdatesCoalesceWithoutAnySectionReferenceOrEventAllocation() {
        var wakeups = new AtomicInteger();
        var router = router(wakeups);
        var section = section(0, 1); // includes fastutil's special zero key slot
        router.watch(section.key, CHILD);
        long arrays = router.childStats().heapArrayBytes();
        for (int i = 0; i < 10000; i++) {
            section._unsafeSetNonEmptyChildren((byte) i);
            router.forwardEvent(section, CHILD);
        }
        assertEquals(arrays, router.childStats().heapArrayBytes());
        assertEquals(1, wakeups.get());
        assertEquals(1, router.childStats().pending());
        assertEquals(9999, router.childStats().coalesced());
        // Raw sections have no tracker: acquiring/releasing them would fail this path.
        assertEquals(1, router.drainChildUpdates((key, mask) -> {
            assertEquals(section.key, key);
            assertEquals((byte) 9999, mask);
        }, 4096));
        assertFalse(router.hasPendingChildren());
    }

    @Test void burstBeyondAdmissionTargetPreservesEveryLatestMaskWithBoundedDrains() {
        var router = router(new AtomicInteger());
        var expected = new HashMap<Long, Byte>();
        for (int i = 0; i < 10000; i++) {
            var section = section(i, i);
            router.watch(section.key, CHILD);
            router.forwardEvent(section, CHILD);
            expected.put(section.key, (byte) i);
        }
        assertTrue(router.childStats().pressure() > 1);
        var actual = new HashMap<Long, Byte>();
        for (int attempts = 0; router.hasPendingChildren() && attempts < 1000; attempts++) {
            assertTrue(router.drainChildUpdates(actual::put, 4096) <= 256);
        }
        assertFalse(router.hasPendingChildren());
        assertEquals(expected, actual);
    }

    @Test void unwatchCancelsPendingAndRewatchCannotReplayOldState() {
        var router = router(new AtomicInteger());
        var section = section(1, 9);
        router.watch(section.key, WorldEngine.DEFAULT_UPDATE_FLAGS);
        router.forwardEvent(section, CHILD);
        assertFalse(router.unwatch(section.key, CHILD));
        assertFalse(router.hasPendingChildren());
        assertEquals(WorldEngine.UPDATE_TYPE_BLOCK_BIT, router.get(section.key));
        router.watch(section.key, CHILD);
        assertEquals(0, router.drainChildUpdates((key, mask) -> fail(), 4096));
        section._unsafeSetNonEmptyChildren((byte) 7);
        router.forwardEvent(section, CHILD);
        router.drainChildUpdates((key, mask) -> assertEquals(7, mask), 4096);
        assertTrue(router.unwatch(section.key, WorldEngine.DEFAULT_UPDATE_FLAGS));
        assertEquals(0, router.childStats().watches());
    }

    @Test void callbackCanChangeWatchesAndPublishNextVersionWithoutDeadlock() {
        var router = router(new AtomicInteger());
        var section = section(4, 1);
        router.watch(section.key, CHILD);
        router.forwardEvent(section, CHILD);
        router.drainChildUpdates((key, mask) -> {
            assertEquals(1, mask);
            router.unwatch(key, CHILD);
            router.watch(key, CHILD);
            section._unsafeSetNonEmptyChildren((byte) 2);
            router.forwardEvent(section, CHILD);
        }, 4096);
        assertTrue(router.hasPendingChildren());
        router.drainChildUpdates((key, mask) -> assertEquals(2, mask), 4096);
        assertFalse(router.hasPendingChildren());
    }

    @Test void resizeRemovalAndTinyScanWindowsCannotStrandDirtyEntries() {
        var mailbox = new ChildUpdateMailbox(() -> {});
        for (int i = 0; i < 2000; i++) {
            var section = section(i, i);
            mailbox.watch(section.key);
            mailbox.offer(section);
        }
        mailbox.drain((key, mask) -> {}, 10);
        for (int i = 0; i < 1900; i++) mailbox.unwatch(section(i, 0).key);
        var found = new HashMap<Long, Byte>();
        for (int i = 0; mailbox.hasPending() && i < 10000; i++) mailbox.drain(found::put, 1);
        assertFalse(mailbox.hasPending());
        for (int i = 1900; i < 2000; i++) {
            var s = section(i, 255);
            mailbox.offer(s);
        }
        found.clear();
        for (int i = 0; mailbox.hasPending() && i < 10000; i++) mailbox.drain(found::put, 1);
        assertEquals(100, found.size());
        assertTrue(found.values().stream().allMatch(mask -> mask == (byte) 255));
    }

    @Test void closeRacingProducersRejectsLateEventsAndReleasesAllWatches() throws Exception {
        var router = router(new AtomicInteger());
        var section = section(3, 5);
        router.watch(section.key, CHILD);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var producer = pool.submit(() -> {
                for (int i = 0; i < 10000; i++) router.forwardEvent(section, CHILD);
            });
            var closer = pool.submit(router::closeChildUpdates);
            producer.get(10, TimeUnit.SECONDS);
            closer.get(10, TimeUnit.SECONDS);
        }
        router.forwardEvent(section, CHILD);
        router.watch(section(4, 7).key, CHILD);
        assertEquals(0, router.childStats().watches());
        assertEquals(0, router.childStats().pending());
        assertEquals(0, router.drainChildUpdates((key, mask) -> fail(), 4096));
    }
}
