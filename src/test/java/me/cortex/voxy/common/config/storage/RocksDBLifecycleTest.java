package me.cortex.voxy.common.config.storage;

import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/** Regression cases adapted from neo-voxy-multiversion's RocksDBLifecycleVerification. */
class RocksDBLifecycleTest {
    @TempDir Path directory;

    @Test
    void preservesAuxDataAcrossReopensAndRejectsAccessAfterClose() {
        byte[] value = {1, 2, 3};
        for (int cycle = 0; cycle < 8; cycle++) {
            var storage = new RocksDBStorageBackend(directory.resolve("reopen").toString());
            try {
                assertTrue(storage.supportsAuxTable("test"));
                if (cycle > 0) assertArrayEquals(value, storage.getAux("test", 1));
                storage.putAux("test", 1, value);
                assertArrayEquals(value, storage.getAux("test", 1));
                storage.putAux("test", 2, value);
                storage.deleteAux("test", 2);
                assertNull(storage.getAux("test", 2));
            } finally {
                storage.close();
            }
            assertDoesNotThrow(storage::close);
            assertFalse(storage.supportsAuxTable("test"));
            assertThrows(IllegalStateException.class, () -> storage.putAux("test", 1, value));
            assertThrows(IllegalStateException.class, () -> storage.getAux("test", 1));
            assertThrows(IllegalStateException.class, () -> storage.deleteAux("test", 1));
            assertThrows(IllegalStateException.class, () -> storage.forEachAux("test", (key, bytes) -> fail()));
            assertThrows(IllegalStateException.class, storage::flush);
            // Missing tables must also reject access before trying to create/use native handles.
            assertThrows(IllegalStateException.class, () -> storage.putAux("new", 1, value));
            assertThrows(IllegalStateException.class, () -> storage.getAux("new", 1));
        }
    }

    @Test
    void closeWaitsForAnActiveAuxIterator() throws Exception {
        var storage = new RocksDBStorageBackend(directory.resolve("concurrent").toString());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closing = new CountDownLatch(1);
        storage.putAux("test", 1, new byte[]{42});
        try (var workers = Executors.newFixedThreadPool(2)) {
            var scan = workers.submit(() -> storage.forEachAux("test", (key, bytes) -> {
                assertArrayEquals(new byte[]{42}, bytes);
                entered.countDown();
                await(release);
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var close = workers.submit(() -> {
                    closing.countDown();
                    storage.close();
                });
                assertTrue(closing.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS),
                        "close must wait until the native iterator has been released");
                release.countDown();
                scan.get(5, TimeUnit.SECONDS);
                close.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        } finally {
            storage.close();
        }
        assertFalse(storage.supportsAuxTable("test"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for iterator release");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
