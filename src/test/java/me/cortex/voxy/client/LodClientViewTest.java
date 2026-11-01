package me.cortex.voxy.client;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LodClientViewTest {
    private static final String DIM = "minecraft:overworld";

    @Test void oldServerUsesOnlyV1AndNoViewMessages() {
        var view = new LodClientView(); var sent = new ArrayList<CustomPacketPayload>();
        assertTrue(view.hello(false, null, 320, 0, sent::add));
        assertEquals(new VoxyNetwork.HelloC2S(1), sent.getFirst());
        view.acknowledge(false);
        view.update(DIM, 1000, 10_000_000_000L, sent::add);
        assertEquals(1, sent.size());
        assertTrue(view.contains(request(600), 0, 0));
    }

    @Test void waitsForLevelAndAcknowledgementThenCoalescesConfigurationChanges() {
        var view = new LodClientView(); var sent = new ArrayList<CustomPacketPayload>();
        assertFalse(view.hello(true, null, 320, 0, sent::add));
        assertTrue(view.hello(true, DIM, 320, 0, sent::add));
        assertEquals(new VoxyNetwork.HelloV2C2S(2, DIM, 320), sent.getFirst());
        view.update(DIM, 800, 2_000_000_000L, sent::add);
        assertEquals(1, sent.size());
        view.acknowledge(true);
        view.update(DIM, 800, 500_000_000, sent::add);
        view.update(DIM, 1600, 999_999_999, sent::add);
        assertEquals(1, sent.size());
        view.update(DIM, 2000, 1_000_000_000, sent::add);
        assertEquals(new VoxyNetwork.ViewDistanceC2S(DIM, 2000), sent.getLast());
        view.update(DIM, 2000, 9_000_000_000L, sent::add);
        assertEquals(2, sent.size());
    }

    @Test void dimensionChangesBypassThrottleFailedSendsRetryAndDisconnectClearsView() {
        var view = new LodClientView(); var sent = new ArrayList<CustomPacketPayload>();
        view.hello(true, DIM, 320, 0, sent::add); view.acknowledge(true);
        assertFalse(view.contains(request(600), 0, 0));
        view.update("minecraft:the_nether", 1000, 1, p -> false);
        assertTrue(view.contains(request(0), 0, 0), "failed update must keep the last advertised view");
        view.update("minecraft:the_nether", Integer.MAX_VALUE, 2, sent::add);
        assertEquals(new VoxyNetwork.ViewDistanceC2S("minecraft:the_nether", 32768), sent.getLast());
        assertFalse(view.contains(request(0), 0, 0));
        view.reset();
        assertTrue(view.contains(request(600), 0, 0));
        view.update(DIM, 320, 3, sent::add);
        assertEquals(2, sent.size());
    }

    @Test void outOfRangeInflightDemandReleasesSlotsAndReturnsAfterRadiusGrowth() {
        var requests = new LodSectionRequests(); var sent = new ArrayList<LodSectionRequests.Request>();
        var view = new LodClientView();
        view.hello(true, DIM, 32768, 0, p -> true); view.acknowledge(true);
        requests.request(DIM, request(600).sectionKey());
        requests.pump(DIM, 0, r -> view.contains(r, 0, 0), sent::add);
        assertEquals(1, requests.inFlightCount());
        view.update(DIM, 320, 1_000_000_000, p -> true);
        requests.request(DIM, request(0).sectionKey());
        requests.pump(DIM, 1_000_000_000, r -> view.contains(r, 0, 0), sent::add);
        assertEquals(1, requests.inFlightCount()); assertEquals(1, requests.pendingCount());
        assertEquals(request(0), sent.getLast());
        requests.complete(request(0), false);
        view.update(DIM, 32768, 2_000_000_000, p -> true);
        requests.pump(DIM, 2_000_000_000, r -> view.contains(r, 0, 0), sent::add);
        assertEquals(request(600), sent.getLast()); assertEquals(0, requests.pendingCount());
    }

    private static LodSectionRequests.Request request(int x) {
        return new LodSectionRequests.Request(DIM, WorldEngine.getWorldSectionId(0, x, 0, 0));
    }
}
