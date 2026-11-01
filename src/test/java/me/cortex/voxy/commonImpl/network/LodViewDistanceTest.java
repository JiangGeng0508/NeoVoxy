package me.cortex.voxy.commonImpl.network;

import io.netty.buffer.Unpooled;
import me.cortex.voxy.common.world.WorldEngine;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static me.cortex.voxy.commonImpl.network.LodServerSchedulerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LodViewDistanceTest {
    @Test void boundsRetainCoarseAndNegativeSectionsAndRejectCornersOutsideCircle() {
        var id = UUID.randomUUID();
        var view = new LodServerScheduler.View(id, DIM, 0, 0, 320);
        assertTrue(view.contains(key(10))); assertFalse(view.contains(key(11)));
        assertTrue(view.contains(key(-11))); assertFalse(view.contains(key(-12)));
        assertTrue(view.contains(new LodUpdateQueue.Key(DIM, WorldEngine.getWorldSectionId(4, -1, 0, -1))));
        assertFalse(view.contains(new LodUpdateQueue.Key(DIM, WorldEngine.getWorldSectionId(0, 8, 0, 8))));
        assertFalse(view.contains(new LodUpdateQueue.Key("minecraft:the_nether", key(0).section())));
        var edge = new LodServerScheduler.View(id, DIM, 0.5, 0, 320);
        assertTrue(edge.contains(key(10))); assertFalse(edge.contains(key(-11)));
        assertEquals(320, new LodServerScheduler.View(id, DIM, 0, 0, Integer.MIN_VALUE).radiusBlocks());
        assertEquals(32768, new LodServerScheduler.View(id, DIM, 0, 0, Integer.MAX_VALUE).radiusBlocks());
    }

    @Test void mixedPeersUseTheirOwnRadiusForPullsPushesAndHandshakeBudgets() {
        var s = new LodServerScheduler(); var t = new Transport(); t.bytes = 0;
        var v1 = UUID.randomUUID(); var v2 = UUID.randomUUID();
        s.hello(new LodServerScheduler.View(v1, DIM, 0, 0, 320)); // V1 must ignore an unnegotiated radius.
        s.helloV2(new LodServerScheduler.View(v2, DIM, 0, 0, 320));
        s.viewDistance(v1, DIM, 320);
        assertTrue(s.request(v1, request(600))); assertFalse(s.request(v2, request(600)));
        assertTrue(s.request(v2, request(10)));
        s.dirty(key(600)); s.tick(t);
        assertInstanceOf(VoxyNetwork.HelloS2C.class, t.sent.get(v1).getFirst());
        assertInstanceOf(VoxyNetwork.HelloV2S2C.class, t.sent.get(v2).getFirst());
        assertTrue(t.sections(v1).stream().anyMatch(p -> p.x() == 600));
        assertEquals(1, t.sections(v2).size()); assertEquals(10, t.sections(v2).getFirst().x());
        assertEquals(t.sent.values().stream().flatMap(java.util.List::stream).mapToInt(VoxyNetwork::encodedSize).sum(), s.stats().lastBytes());
        assertEquals(0, s.updates.stats().pending()); s.close();
    }

    @Test void radiusShrinkDropsPendingPullsAndPushesButGrowthAndMovementCanRequestAgain() {
        var s = new LodServerScheduler(); var t = new Transport(); t.bytes = 0;
        var id = UUID.randomUUID();
        s.helloV2(view(id)); s.request(id, request(100)); s.dirty(key(100));
        s.viewDistance(id, DIM, 320); s.tick(t);
        assertEquals(0, t.sections(id).size()); assertEquals(0, s.stats().requests());
        assertEquals(0, s.updates.stats().pending()); assertFalse(s.interested(key(100)));
        s.viewDistance(id, DIM, 4000);
        assertTrue(s.interested(key(100))); assertTrue(s.request(id, request(100))); s.tick(t);
        assertEquals(1, t.sections(id).size());
        s.viewDistance(id, DIM, 320);
        s.updateView(new LodServerScheduler.View(id, DIM, 3200, 0)); s.tick(t);
        assertTrue(s.interested(key(100))); assertFalse(s.request(id, request(0))); s.close();
    }

    @Test void mappingRepairDimensionSwitchAndLateOldDimensionUpdatesPreserveNegotiatedRadius() {
        var s = new LodServerScheduler(); var t = new Transport(); t.bytes = 0;
        var id = UUID.randomUUID(); var small = new LodServerScheduler.View(id, DIM, 0, 0, 320);
        s.helloV2(small); s.request(id, request(1));
        s.resync(view(id));
        assertEquals(1, s.stats().requests()); assertFalse(s.request(id, request(600)));
        s.updateView(new LodServerScheduler.View(id, "minecraft:the_nether", 0, 0));
        assertEquals(0, s.stats().requests());
        s.viewDistance(id, DIM, 32768);
        assertFalse(s.request(id, new VoxyNetwork.SectionRequestC2S("minecraft:the_nether", 0, 600, 0, 0)));
        s.tick(t); assertInstanceOf(VoxyNetwork.HelloV2S2C.class, t.sent.get(id).getFirst());
        s.logout(id); s.viewDistance(id, DIM, 32768); s.hello(view(id));
        assertTrue(s.request(id, request(600))); s.tick(t);
        assertTrue(t.sent.get(id).stream().anyMatch(VoxyNetwork.HelloS2C.class::isInstance)); s.close();
    }

    @Test void optionalCodecsRoundTripAndHelloAccountingUsesActualBytes() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var hello = new VoxyNetwork.HelloV2C2S(2, DIM, 32768);
            VoxyNetwork.HelloV2C2S.CODEC.encode(buf, hello);
            assertEquals(hello, VoxyNetwork.HelloV2C2S.CODEC.decode(buf)); buf.clear();
            var update = new VoxyNetwork.ViewDistanceC2S("example:世界", Integer.MIN_VALUE);
            VoxyNetwork.ViewDistanceC2S.CODEC.encode(buf, update);
            assertEquals(update, VoxyNetwork.ViewDistanceC2S.CODEC.decode(buf)); buf.clear();
            var ack = new VoxyNetwork.HelloV2S2C(2);
            VoxyNetwork.HelloV2S2C.CODEC.encode(buf, ack);
            assertEquals(ack, VoxyNetwork.HelloV2S2C.CODEC.decode(buf)); buf.clear();
            VoxyNetwork.HelloV2S2C.CODEC.encode(buf, ack); checkSize(buf, ack);
        } finally { buf.release(); }
    }
}
