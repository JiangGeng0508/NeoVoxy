package me.cortex.voxy.client.distgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class DistantGenerationShutdownTest {
    @Test void shutdownReleasesEveryOriginalTicketIncludingCollidingAndZeroKeys() {
        var tasks = new Long2ObjectOpenHashMap<CompletableFuture<?>>();
        var expected = new LongOpenHashSet();
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) {
                long pos = ChunkPos.asLong(x, z);
                tasks.put(pos, new CompletableFuture<>());
                expected.add(pos);
            }
        }
        var futures = tasks.values().toArray(CompletableFuture[]::new);
        var released = new LongOpenHashSet();
        DistantWorldGenerator.releaseInFlightTickets(tasks,
                pos -> assertTrue(released.add(pos.toLong()), "ticket released twice: " + pos));
        assertEquals(expected, released, "hash-table deletion must not change the position being released");
        assertTrue(tasks.isEmpty());
        for (var future : futures) assertFalse(future.isDone(), "vanilla owns generation future completion");
        DistantWorldGenerator.releaseInFlightTickets(tasks, pos -> fail("duplicate shutdown release"));
    }

    @Test void failedReleaseRetainsTicketForAnotherShutdownAttempt() {
        var tasks = new Long2ObjectOpenHashMap<Object>();
        long pos = ChunkPos.asLong(-10, 42);
        tasks.put(pos, new Object());
        assertThrows(IllegalStateException.class, () -> DistantWorldGenerator.releaseInFlightTickets(tasks,
                cp -> { throw new IllegalStateException("release failed"); }));
        assertTrue(tasks.containsKey(pos));
        DistantWorldGenerator.releaseInFlightTickets(tasks, cp -> assertEquals(pos, cp.toLong()));
        assertTrue(tasks.isEmpty());
    }
}
