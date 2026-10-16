package me.cortex.voxy.client.compat.littletiles;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesCompat;
import me.cortex.voxy.commonImpl.compat.littletiles.LittleTilesStore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class LittleTilesStorageLifecycleTest {
    private final LittleTilesDistantRenderer renderer = new LittleTilesDistantRenderer();
    private final List<WorldEngine> engines = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        renderer.logout(null);
        for (var engine : engines) if (engine.isLive()) engine.free();
        var active = LittleTilesDistantRenderer.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, null);
    }

    @Test
    void rebindingClosedStorageCarriesLatestSnapshotsAndDeletions() throws Exception {
        var old = engine();
        var next = engine();
        var unrelated = engine();
        var oldStorage = storage(old);
        var nextStorage = storage(next);
        bind(old);
        for (int x = 1; x <= 3; x++) LittleTilesDistantRenderer.accept(oldStorage, snapshot(x, 10));
        drain();
        LittleTilesStore.save(nextStorage, snapshot(1, 20));
        LittleTilesStore.save(nextStorage, snapshot(2, 20));
        LittleTilesStore.save(nextStorage, snapshot(7, 20));

        LittleTilesDistantRenderer.accept(oldStorage, empty(1));
        LittleTilesDistantRenderer.accept(oldStorage, snapshot(2, 30));
        LittleTilesDistantRenderer.accept(oldStorage, snapshot(2, 40));
        LittleTilesDistantRenderer.accept(oldStorage, snapshot(4, 40));
        LittleTilesDistantRenderer.accept(nextStorage, snapshot(2, 50));
        LittleTilesDistantRenderer.accept(nextStorage, snapshot(5, 50));
        LittleTilesDistantRenderer.accept(storage(unrelated), snapshot(6, 60));
        old.free();

        // A null level is a stable identity here; no camera or GPU work is needed to rebind.
        bind(next);
        drain();
        assertEquals(0, oldStorage.closedAccesses, "rebinding must not query or write the old storage");
        assertNull(LittleTilesStore.load(nextStorage, 1, 0, 0), "pending deletion must override disk data");
        assertColor(nextStorage, 2, 50); // New storage updates win over carried old updates.
        assertColor(nextStorage, 3, 10); // Already applied in-memory snapshots survive.
        assertColor(nextStorage, 4, 40);
        assertColor(nextStorage, 5, 50);
        assertNull(LittleTilesStore.load(nextStorage, 6, 0, 0));
        assertColor(nextStorage, 7, 20);
        assertEquals(Set.of(key(2), key(3), key(4), key(5), key(7)), sectionKeys());
    }

    @Test
    void checkpointAndLogoutSkipAnEngineThatHasAlreadyClosed() throws Exception {
        LittleTilesDistantRenderer.checkpointActive(); // No bound engine yet.
        var engine = engine();
        var storage = storage(engine);
        bind(engine);
        LittleTilesDistantRenderer.accept(storage, snapshot(1, 10));
        drain();
        engine.free();
        LittleTilesDistantRenderer.accept(storage, empty(1));
        LittleTilesDistantRenderer.checkpointActive();
        renderer.logout(null);
        assertEquals(0, storage.closedAccesses);
        assertTrue(sectionKeys().isEmpty());
    }

    @Test
    void logoutCheckpointsTheLiveWorldWithoutCarryingItIntoTheNextWorld() throws Exception {
        var old = engine();
        var oldStorage = storage(old);
        bind(old);
        LittleTilesDistantRenderer.accept(oldStorage, snapshot(1, 10));
        renderer.logout(null);
        assertColor(oldStorage, 1, 10);
        assertTrue(oldStorage.flushes > 0);

        var next = engine();
        var nextStorage = storage(next);
        LittleTilesDistantRenderer.accept(oldStorage, snapshot(2, 20)); // Late old-world publication.
        LittleTilesDistantRenderer.accept(nextStorage, snapshot(3, 30));
        bind(next);
        drain();
        assertEquals(Set.of(key(3)), sectionKeys());
        assertNull(LittleTilesStore.load(nextStorage, 1, 0, 0));
        assertNull(LittleTilesStore.load(nextStorage, 2, 0, 0));
        assertColor(nextStorage, 3, 30);
    }

    @Test
    void publicationDuringRebindingIsAppliedAfterLoadedSnapshots() throws Exception {
        var engine = engine();
        var storage = storage(engine);
        LittleTilesStore.save(storage, snapshot(1, 10));
        var scanning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var publishing = new CountDownLatch(1);
        storage.beforeScan = () -> {
            scanning.countDown();
            await(release);
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            var binding = workers.submit(() -> { bind(engine); return null; });
            try {
                assertTrue(scanning.await(5, TimeUnit.SECONDS));
                var publish = workers.submit(() -> {
                    publishing.countDown();
                    LittleTilesDistantRenderer.accept(storage, snapshot(1, 20));
                });
                assertTrue(publishing.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> publish.get(100, TimeUnit.MILLISECONDS),
                        "publication must not interleave with the queue migration");
                release.countDown();
                binding.get(5, TimeUnit.SECONDS);
                publish.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        storage.beforeScan = null;
        drain();
        assertColor(storage, 1, 20);
        assertEquals(Set.of(key(1)), sectionKeys());
    }

    private WorldEngine engine() {
        var engine = new WorldEngine(new RecordingStorage());
        engines.add(engine);
        return engine;
    }

    private static RecordingStorage storage(WorldEngine engine) {
        return (RecordingStorage) engine.storage;
    }

    private void bind(WorldEngine engine) throws Exception {
        method("bindStorage", WorldEngine.class, ClientLevel.class).invoke(renderer, engine, null);
    }

    private void drain() throws Exception {
        method("drainUpdates", int.class, Vec3.class, double.class).invoke(renderer, Integer.MAX_VALUE, null, 0.0);
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        var method = LittleTilesDistantRenderer.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private Set<?> sectionKeys() throws Exception {
        var field = LittleTilesDistantRenderer.class.getDeclaredField("sections");
        field.setAccessible(true);
        return Set.copyOf(((Map<?, ?>) field.get(renderer)).keySet());
    }

    private static long key(int x) { return LittleTilesStore.key(x, 0, 0); }

    private static LittleTilesCompat.SectionSnapshot snapshot(int x, int color) {
        return new LittleTilesCompat.SectionSnapshot(x, 0, 0,
                List.of(new LittleTilesCompat.Material(Blocks.STONE.defaultBlockState(), color)),
                List.of(new LittleTilesCompat.Cell(0, 0, 15)), new long[64]);
    }

    private static LittleTilesCompat.SectionSnapshot empty(int x) {
        return new LittleTilesCompat.SectionSnapshot(x, 0, 0, List.of(), List.of(), new long[64]);
    }

    private static void assertColor(SectionStorage storage, int x, int color) {
        var snapshot = LittleTilesStore.load(storage, x, 0, 0);
        assertNotNull(snapshot, "section " + x);
        assertEquals(color, snapshot.materials().getFirst().color(), "section " + x);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for migration release");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Records all stale access, including calls caught and logged by LittleTilesStore. */
    private static class RecordingStorage extends SectionSerializationStorage {
        private final Map<String, Map<Long, byte[]>> tables = new HashMap<>();
        private boolean closed;
        int closedAccesses;
        int flushes;
        Runnable beforeScan;

        RecordingStorage() { super(new MemoryStorageBackend()); }

        private void requireOpen() {
            if (closed) {
                closedAccesses++;
                throw new IllegalStateException("test storage is closed");
            }
        }

        @Override public boolean supportsAuxTable(String table) { requireOpen(); return true; }
        @Override public void putAux(String table, long key, byte[] value) {
            requireOpen();
            tables.computeIfAbsent(table, ignored -> new HashMap<>()).put(key, value.clone());
        }
        @Override public byte[] getAux(String table, long key) {
            requireOpen();
            return tables.getOrDefault(table, Map.of()).get(key);
        }
        @Override public void deleteAux(String table, long key) {
            requireOpen();
            var entries = tables.get(table);
            if (entries != null) entries.remove(key);
        }
        @Override public void forEachAux(String table, StorageBackend.AuxEntryConsumer consumer) {
            requireOpen();
            if (table.equals(LittleTilesStore.TABLE) && beforeScan != null) beforeScan.run();
            tables.getOrDefault(table, Map.of()).forEach(consumer::accept);
        }
        @Override public void flush() { requireOpen(); flushes++; super.flush(); }
        @Override public void close() { requireOpen(); super.close(); closed = true; }
    }
}
