package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static me.cortex.voxy.commonImpl.network.LodServerSchedulerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LodServerPreparationDispatchTest {
    static final class Transport extends LodServerSchedulerTest.Transport {
        final WorldEngine world;
        final LodServerPreparation prep;
        Transport(WorldEngine world, LodServerPreparation prep) { this.world = world; this.prep = prep; }
        @Override public LodServerScheduler.Mappings nextMappings(UUID id, int block, int biome, int credit) { return prep.mappings(world, block, biome); }
        @Override public VoxyNetwork.SectionDataS2C load(LodUpdateQueue.Key key) { return prep.section(world, key); }
        @Override public boolean current(LodUpdateQueue.Key key, VoxyNetwork.SectionDataS2C data) { return prep.current(world, key, data); }
    }

    @Test void twoRecipientsShareOnePreparationAndRepairMappingsBeforeSendingItsSnapshot() {
        var world = LodServerPreparationTest.world(); var manager = new ServiceManager(n -> {});
        var loader = new LodServerPreparationTest.Loader();
        loader.duringLoad = () -> world.getMapper().getIdForBlockState(Blocks.GOLD_BLOCK.defaultBlockState());
        var prep = new LodServerPreparation(manager, loader, 4L << 20, 4, 16);
        var s = new LodServerScheduler(); var t = new Transport(world, prep);
        var a = UUID.randomUUID(); var b = UUID.randomUUID();
        try {
            s.hello(view(a)); s.hello(view(b)); s.request(a, request(1)); s.request(b, request(1));
            for (int tick = 0; tick < 10 && s.stats().requests() != 0; tick++) {
                s.tick(t);
                while (manager.tryRunAJob() == 0) {}
            }
            assertEquals(0, s.stats().requests()); assertEquals(1, loader.loads);
            assertEquals(1, prep.stats().sectionsPrepared()); assertEquals(2, prep.stats().mappingPagesPrepared());
            assertSame(t.sections(a).getFirst(), t.sections(b).getFirst());
            for (var player : List.of(a, b)) {
                var packets = t.sent.get(player);
                assertEquals(2, packets.stream().filter(VoxyNetwork.BlockMapBatchS2C.class::isInstance).count());
                assertInstanceOf(VoxyNetwork.SectionDataS2C.class, packets.getLast());
            }
        } finally { s.close(); prep.close(); manager.shutdown(); world.free(); }
    }

    @Test void sendFailureRetainsTicketAndReusesPreparedBytesUntilDirtyInvalidatesThem() {
        var world = LodServerPreparationTest.world(); var manager = new ServiceManager(n -> {});
        var loader = new LodServerPreparationTest.Loader();
        var prep = new LodServerPreparation(manager, loader, 4L << 20, 4, 16);
        var s = new LodServerScheduler(); var t = new Transport(world, prep); var player = UUID.randomUUID();
        try {
            s.hello(view(player));
            for (int i = 0; i < 3; i++) { s.tick(t); while (manager.tryRunAJob() == 0) {} }
            s.dirty(key(1)); t.failSend = true;
            s.tick(t); assertEquals(0, manager.tryRunAJob());
            s.tick(t); assertEquals(1, s.updates.stats().pending()); assertEquals(1, loader.loads);
            loader.version = 2; prep.invalidate(world, key(1)); s.dirty(key(1));
            s.tick(t); assertEquals(0, manager.tryRunAJob());
            t.failSend = false; s.tick(t);
            assertEquals(2, t.sections(player).getLast().data()[0]);
            assertEquals(2, loader.loads); assertEquals(0, s.updates.stats().pending());
        } finally { s.close(); prep.close(); manager.shutdown(); world.free(); }
    }
}
