package me.cortex.voxy.client.compat.domum;

import me.cortex.voxy.client.compat.create.DistantMesh;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.commonImpl.compat.DisguiseStore;
import me.cortex.voxy.commonImpl.compat.DomumOrnamentumCompat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.function.Supplier;

import static me.cortex.voxy.testing.Reflect.*;
import static org.junit.jupiter.api.Assertions.*;

class DomumRebuildTest {
    private final DomumDistantRenderer renderer = new DomumDistantRenderer();

    @AfterEach
    void cleanup() throws Exception {
        renderer.logout(null);
        clearStatic(DomumDistantRenderer.class, "active");
        DomumOrnamentumCompat.sectionListener = null;
    }

    @Test
    void unchangedSnapshotsKeepTheEntryAndOnlyOneQueuedBake() throws Exception {
        long key = DisguiseStore.keyOf(0, 0, 0);
        call(renderer, "install", key, data(1), true);
        Object entry = entries().get(key);
        Object blocks = get(entry, "blocks");
        scan(8, 8, 8, 64, 1000);
        call(renderer, "install", key, data(1), true);
        assertSame(entry, entries().get(key));
        assertSame(blocks, get(entry, "blocks"));
        call(renderer, "install", key, data(2), true);
        assertSame(entry, entries().get(key));
        assertArrayEquals(new int[]{0, 2}, get(entry, "blocks"));
        assertEquals(1, queue().size());
        call(renderer, "install", key, null, true);
        assertFalse(entries().containsKey(key));
        assertTrue(queue().isEmpty(), "deletion must cancel queued replacement work");
    }

    @Test
    void duplicateNotificationsReadTheLatestStorageValueOncePerBatch() throws Exception {
        var storage = new SectionSerializationStorage(new MemoryStorageBackend()) {
            int reads;
            @Override public byte[] getAux(String table, long key) { reads++; return data(7); }
        };
        try {
            set(renderer, "storage", storage);
            for (int i = 0; i < 10; i++) DomumOrnamentumCompat.sectionListener.changed(storage, 0, 0, 0);
            call(renderer, "drainUpdates", 128);
            assertEquals(1, storage.reads);
            assertArrayEquals(new int[]{0, 7}, get(entries().get(DisguiseStore.keyOf(0, 0, 0)), "blocks"));
        } finally {
            storage.close();
        }
    }

    @Test
    void prefetchRespondsToVerticalMovementAndViewDistanceChanges() throws Exception {
        long key = DisguiseStore.keyOf(0, 10, 0);
        call(renderer, "install", key, data(1), false);
        scan(8, 8, 8, 64, 1000); // 160 blocks away, beyond the 128-block preparation radius.
        assertTrue(queue().isEmpty());
        scan(8, 72, 8, 64, 1001); // Vertical movement only; 96 blocks away, in prefetch but outside draw range.
        assertEquals(key, queue().getFirst());
        renderer.logout(null);
        call(renderer, "install", key, data(1), false);
        scan(8, 8, 8, 64, 2000);
        assertTrue(queue().isEmpty());
        scan(8, 8, 8, 128, 2001);
        assertEquals(key, queue().getFirst());
    }

    @Test
    void failedAndEmptyBakesRetryWithoutCameraMovement() throws Exception {
        long key = DisguiseStore.keyOf(0, 0, 0);
        call(renderer, "install", key, data(1), true);
        Object entry = entries().get(key);
        call(renderer, "rebuild", entry, 1000L, (Supplier<DistantMesh>) () -> null);
        assertEquals(2000L, (long) get(entry, "retryAtMs"));
        scan(8, 8, 8, 64, 1000);
        scan(8, 8, 8, 64, 1999);
        assertTrue(queue().isEmpty());
        scan(8, 8, 8, 64, 2000);
        assertEquals(key, queue().getFirst());
        call(renderer, "rebuild", entry, 2000L, (Supplier<DistantMesh>) () -> {
            throw new IllegalStateException("expected test bake failure");
        });
        assertTrue((boolean) get(entry, "needsBake"));
        assertEquals(3000L, (long) get(entry, "retryAtMs"));
    }

    private Map<Long, Object> entries() throws Exception { return get(renderer, "sections"); }
    private ArrayDeque<Long> queue() throws Exception { return get(renderer, "bakeQueue"); }
    private void scan(double x, double y, double z, double range, long now) throws Exception {
        call(renderer, "refreshCandidates", x, y, z, range, now);
    }

    public static byte[] data(int variant) {
        return ByteBuffer.allocate(9).put((byte) 1).putShort((short) 1).putShort((short) 0).putInt(variant).array();
    }
}
