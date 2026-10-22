package me.cortex.voxy.audit;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyServerInstance;
import me.cortex.voxy.commonImpl.network.LodServerScheduler;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;

@EventBusSubscriber(modid = "voxy", value = Dist.DEDICATED_SERVER)
public final class NetworkStressServer {
    private static int tick, started = -1, maxPackets, maxBytes;
    private static long heapBefore, heapPeak;
    private static boolean done;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.networkStress");
        if (output == null || done) return;
        tick++;
        if (!(VoxyCommon.getInstance() instanceof VoxyServerInstance server)) return;
        var stats = server.networkStats();
        if (stats.lastPackets() > LodServerScheduler.PACKETS_PER_TICK || stats.lastBytes() > LodServerScheduler.BYTES_PER_TICK)
            throw new AssertionError("network tick budget exceeded: " + stats);
        maxPackets = Math.max(maxPackets, stats.lastPackets()); maxBytes = Math.max(maxBytes, stats.lastBytes());
        if (server.updateStats().pending() > LodServerScheduler.MAX_UPDATES) throw new AssertionError("unbounded queue");
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        heapPeak = Math.max(heapPeak, heap);
        Path base = Path.of(output);
        if (started < 0 && Files.exists(base.resolve("Fast.ready")) && Files.exists(base.resolve("Slow.ready"))) {
            started = tick; heapBefore = heap;
        }
        if (started < 0) return;
        int elapsed = tick - started;
        if (elapsed == 10 || elapsed == 20 || elapsed == 30) {
            var engine = server.getEngineForLevel(event.getServer().overworld());
            var state = (elapsed == 10 ? Blocks.STONE : elapsed == 20 ? Blocks.DIRT : Blocks.GOLD_BLOCK).defaultBlockState();
            int block = engine.getMapper().getIdForBlockState(state);
            // 2304 distinct keys overflow the 2048-slot mailbox. Updates are real stored sections.
            for (int i = 0; i < 2304; i++) {
                var section = engine.acquire(0, 200 + i % 48, 0, 200 + i / 48);
                try {
                    section.setUniform(Mapper.composeMappingId((byte) 15, block, 0));
                    section._unsafeSetNonEmptyChildren((byte) 255);
                    section.addNonEmptyBlockCount(WorldSection.SECTION_VOLUME - section.getNonEmptyBlockCount());
                    for (int repeat = 0; repeat < 3; repeat++) engine.markDirty(section);
                } finally { section.release(); }
            }
            System.out.println("NETWORK_STRESS revision=" + elapsed / 10 + " queue=" + server.updateStats());
        }
        if (elapsed % 100 == 0) {
            System.out.println("NETWORK_STRESS tick=" + elapsed + " " + stats + " " + server.updateStats()
                    + " sweeps=" + server.recoverySweeps() + " spoolBytes=" + server.recoverySpoolBytes());
            for (var p : event.getServer().getPlayerList().getPlayers()) System.out.println("NETWORK_STRESS peer=" + p.getGameProfile().getName()
                    + " id=" + p.getUUID() + " writable=" + p.connection.getConnection().channel().isWritable()
                    + " sent=" + p.connection.getConnection().getAverageSentPackets());
        }
        if (elapsed > 300 && Files.exists(base.resolve("Fast.pass")) && Files.exists(base.resolve("Slow.pass"))
                && server.updateStats().pending() == 0 && server.updateStats().repairs() == 0) {
            if (server.updateStats().overflow() == 0 || server.updateStats().coalesced() == 0 || server.recoverySweeps() == 0 || stats.unwritable() == 0)
                throw new AssertionError("stress did not exercise coalescing/overflow/recovery");
            String result = "NETWORK_STRESS SERVER PASS " + stats + " " + server.updateStats()
                    + " maxPackets=" + maxPackets + " maxBytes=" + maxBytes + " recoverySweeps=" + server.recoverySweeps()
                    + " spoolBytes=" + server.recoverySpoolBytes() + " heapBefore=" + heapBefore + " heapPeak=" + heapPeak;
            Files.writeString(base.resolve("server.pass"), result);
            System.out.println(result); done = true;
        }
        if (elapsed > 5400) throw new AssertionError("NETWORK_STRESS server timeout");
    }
}
