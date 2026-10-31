package me.cortex.voxy.common.thread;

import me.cortex.voxy.common.util.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ServiceManagerTest {
    @Test void weightsDoNotGrowWithBacklogAndEveryRoundServesSmallQueues() {
        var manager = new ServiceManager(n -> {});
        var runs = new ArrayList<String>();
        var ingest = manager.createServiceNoCleanup(() -> () -> runs.add("i"), 4);
        var mesh = manager.createServiceNoCleanup(() -> () -> runs.add("m"), 4);
        var save = manager.createServiceNoCleanup(() -> () -> runs.add("s"), 2);
        var network = manager.createServiceNoCleanup(() -> () -> runs.add("n"), 2);
        var importer = manager.createServiceNoCleanup(() -> () -> runs.add("x"), 1);
        try {
            for (int i = 0; i < 10_000; i++) ingest.execute();
            for (var service : List.of(mesh, save, network, importer)) for (int i = 0; i < 10; i++) service.execute();
            for (int i = 0; i < 26; i++) assertEquals(0, manager.tryRunAJob());
            var round = List.of("i", "i", "i", "i", "m", "m", "m", "m", "s", "s", "n", "n", "x");
            assertEquals(round, runs.subList(0, 13));
            assertEquals(round, runs.subList(13, 26));
        } finally {
            for (var service : List.of(ingest, mesh, save, network, importer)) service.shutdown();
            manager.shutdown();
        }
    }

    @Test void emptyAndLimitedServicesYieldAndRecoverWithoutLosingJobs() {
        var manager = new ServiceManager(n -> {});
        var allowed = new AtomicBoolean();
        var count = new AtomicInteger();
        var empty = manager.createServiceNoCleanup(() -> () -> fail("empty service ran"), 4);
        var limited = manager.createService(() -> new Pair<>(count::incrementAndGet, () -> {}), 4, "limited", allowed::get);
        var other = manager.createServiceNoCleanup(() -> count::incrementAndGet, 1);
        try {
            limited.execute(); other.execute();
            assertEquals(0, manager.tryRunAJob());
            assertEquals(1, count.get());
            assertEquals(3, manager.tryRunAJob());
            allowed.set(true);
            assertEquals(0, manager.tryRunAJob());
            assertEquals(2, count.get());
            assertEquals(1, manager.tryRunAJob());
        } finally { empty.shutdown(); limited.shutdown(); other.shutdown(); manager.shutdown(); }
    }

    @Test void removeAndAddServicesResetsRoundAndStealsKeepCountsConsistent() {
        var manager = new ServiceManager(n -> {});
        var a = manager.createServiceNoCleanup(() -> () -> {}, 4);
        a.execute(); a.execute();
        assertTrue(a.steal());
        assertEquals(0, manager.tryRunAJob());
        a.shutdown();
        var b = manager.createServiceNoCleanup(() -> () -> {}, 1);
        b.execute();
        assertEquals(0, manager.tryRunAJob());
        assertEquals(1, manager.tryRunAJob());
        b.shutdown(); manager.shutdown();
        assertThrows(IllegalStateException.class, () -> manager.createServiceNoCleanup(() -> () -> {}, 1));
    }

    @Test void shutdownWaitsForClaimedInvocationBeforeFreeingContext() throws Exception {
        var manager = new ServiceManager(n -> {});
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var cleaned = new AtomicBoolean();
        var service = manager.createService(() -> new Pair<>(() -> {
            entered.countDown();
            try { finish.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            assertFalse(cleaned.get());
        }, () -> cleaned.set(true)), 1);
        service.execute(); service.execute();
        var worker = new Thread(manager::tryRunAJob);
        worker.start(); entered.await();
        var stopping = new Thread(service::shutdown);
        stopping.start();
        finish.countDown();
        worker.join(); stopping.join();
        assertTrue(cleaned.get());
        assertEquals(0, service.numJobs());
        manager.shutdown();
    }

    @Test void concurrentWorkersExecuteEachPermitExactlyOnce() throws Exception {
        var pool = new UnifiedServiceThreadPool();
        var count = new AtomicInteger();
        var done = new CountDownLatch(4000);
        var service = pool.serviceManager.createServiceNoCleanup(() -> () -> { count.incrementAndGet(); done.countDown(); }, 4);
        pool.setNumThreads(4);
        for (int i = 0; i < 4000; i++) service.execute();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        service.shutdown(); pool.shutdown();
        assertEquals(4000, count.get());
    }

    @Test void zeroAndNegativeWeightsAreRejected() {
        var manager = new ServiceManager(n -> {});
        assertThrows(IllegalArgumentException.class, () -> manager.createServiceNoCleanup(() -> () -> {}, 0));
        assertThrows(IllegalArgumentException.class, () -> manager.createServiceNoCleanup(() -> () -> {}, -1));
        manager.shutdown();
    }

    @Test void nestedHelpingConsumesItsOwnTurnsWithoutCorruptingTheOuterRound() {
        var manager = new ServiceManager(n -> {});
        var helped = new AtomicBoolean();
        var runs = new ArrayList<String>();
        var first = manager.createServiceNoCleanup(() -> () -> {
            runs.add("a");
            if (!helped.getAndSet(true)) assertEquals(0, manager.tryRunAJob());
        }, 2);
        var second = manager.createServiceNoCleanup(() -> () -> runs.add("b"), 1);
        try {
            for (int i = 0; i < 4; i++) first.execute();
            second.execute(); second.execute();
            for (int i = 0; i < 5; i++) assertEquals(0, manager.tryRunAJob());
            assertEquals(List.of("a", "a", "b", "a", "a", "b"), runs);
            assertEquals(1, manager.tryRunAJob());
        } finally { first.shutdown(); second.shutdown(); manager.shutdown(); }
    }
}
