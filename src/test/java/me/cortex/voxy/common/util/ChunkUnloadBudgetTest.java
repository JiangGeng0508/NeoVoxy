package me.cortex.voxy.common.util;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChunkUnloadBudgetTest {
    @Test void completedSaveFutureCannotStarveGenerationReferenceRelease() {
        var holder = new ChunkHolder(new ChunkPos(0, 0), 33, LevelHeightAccessor.create(0, 256), null, null, null);
        assertTrue(holder.getSaveSyncFuture().isDone());
        holder.increaseGenerationRefCount();
        Queue<Runnable> unloads = new ArrayDeque<>();
        AtomicInteger saved = new AtomicInteger();
        // Same retry condition and executor as ChunkMap.scheduleUnload. Generation can
        // reacquire a holder after scheduleUnload observed a zero reference count.
        Runnable retry = new Runnable() {
            @Override public void run() {
                if (!holder.isReadyForSaving()) holder.getSaveSyncFuture().thenRunAsync(this, unloads::add);
                else saved.incrementAndGet();
            }
        };
        holder.getSaveSyncFuture().thenRunAsync(retry, unloads::add);
        assertEquals(1, drain(unloads));
        assertEquals(0, saved.get());
        assertEquals(1, unloads.size(), "pending unload must survive the yield");
        // The server can now pump the chunk task that releases the generation claim.
        holder.decreaseGenerationRefCount();
        assertEquals(1, drain(unloads));
        assertEquals(1, saved.get());
        assertTrue(unloads.isEmpty());
    }

    @Test void fifoAndAsyncCompletionsSurviveAcrossDrainInvocations() {
        Queue<Runnable> queue = new ArrayDeque<>();
        var order = new StringBuilder();
        var pending = new CompletableFuture<Void>();
        pending.thenRunAsync(() -> order.append('d'), queue::add);
        queue.add(() -> { order.append('a'); queue.add(() -> order.append('c')); });
        queue.add(() -> { order.append('b'); pending.complete(null); });
        assertEquals(2, drain(queue));
        assertEquals("ab", order.toString());
        assertEquals(2, drain(queue));
        assertEquals("abcd", order.toString());
        assertEquals(0, drain(queue));
    }

    @Test void emptyInvocationDoesNotGainMoreAllowanceFromNewTasks() {
        var budget = new ChunkUnloadBudget();
        Queue<Runnable> queue = new ArrayDeque<>();
        assertFalse(budget.take(queue));
        queue.add(() -> {});
        assertFalse(budget.take(queue));
        var next = new ChunkUnloadBudget();
        assertTrue(next.take(queue));
        assertFalse(next.take(queue));
    }

    @Test void queueLengthIsSampledOnlyOncePerInvocation() {
        var reads = new AtomicInteger();
        Queue<Runnable> queue = new ArrayDeque<>() {
            @Override public int size() { reads.incrementAndGet(); return super.size(); }
        };
        for (int i = 0; i < 8; i++) queue.add(() -> {});
        assertEquals(8, drain(queue));
        assertEquals(1, reads.get(), "concurrent queue length traversal must not become quadratic");
    }

    private static int drain(Queue<Runnable> queue) {
        var budget = new ChunkUnloadBudget();
        int ran = 0;
        while (budget.take(queue)) {
            var task = queue.poll();
            if (task == null) break;
            task.run();
            assertTrue(++ran <= 10, "unload drain did not yield");
        }
        return ran;
    }
}
