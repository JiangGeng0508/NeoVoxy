package me.cortex.voxy.client;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VoxyClientNetworkTest {
    private VoxyClientNetwork handler;
    private LodSectionRequests originalRequests;
    private boolean originalAuthoritative;
    private long originalHelloTime;

    @BeforeEach
    void isolateSession() throws Exception {
        var constructor = VoxyClientNetwork.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        this.handler = constructor.newInstance();
        this.originalRequests = get("sectionRequests");
        this.originalAuthoritative = get("serverAuthoritative");
        this.originalHelloTime = get("lastHelloNanos");
        set("sectionRequests", new LodSectionRequests());
        set("serverAuthoritative", false);
    }

    @AfterEach
    void restoreSession() throws Exception {
        VoxyClientNetwork.onLoggingOut(null);
        set("sectionRequests", this.originalRequests);
        set("serverAuthoritative", this.originalAuthoritative);
        set("lastHelloNanos", this.originalHelloTime);
    }

    @Test
    void storeBindingDoesNotRequireAnInitializedMinecraftConnection() throws Exception {
        // World-engine creation can precede Minecraft exposing its connection. Binding
        // must be lazy; availability is checked when a miss occurs, not once at creation.
        var listener = VoxyClientNetwork.missListener("minecraft:overworld");
        assertNotNull(listener);
        listener.onMissing(key(1));
        LodSectionRequests requests = get("sectionRequests");
        assertEquals(1, requests.pendingCount(), "early misses must survive until connection/hello are ready");
    }

    @Test
    void helloResyncKeepsRequestsAndEmptyRepliesReleaseSlotsWithoutRepeatingAbsences() throws Exception {
        LodSectionRequests requests = get("sectionRequests");
        for (int i = 0; i < 600; i++) requests.request("minecraft:overworld", key(i));
        assertFalse(VoxyClientNetwork.isServerAuthoritative());
        this.handler.handleHello(new VoxyNetwork.HelloS2C(-1));
        assertFalse(VoxyClientNetwork.isServerAuthoritative());
        assertEquals(600, requests.pendingCount());
        this.handler.handleHello(new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION));
        assertTrue(VoxyClientNetwork.isServerAuthoritative());
        var sent = new ArrayList<LodSectionRequests.Request>();
        for (int i = 0; i < 10; i++) requests.pump("minecraft:overworld", 0, sent::add);
        assertEquals(512, requests.inFlightCount());
        this.handler.handleHello(new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION));
        assertEquals(512, requests.inFlightCount(), "resync must not forget requests already sent");
        for (int i = 0; i < 512; i++) {
            this.handler.handleSectionData(new VoxyNetwork.SectionDataS2C("minecraft:overworld", 0, i, 0, 0, new byte[0]));
        }
        assertEquals(0, requests.inFlightCount());
        this.handler.handleHello(new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION));
        for (int i = 0; i < 512; i++) requests.request("minecraft:overworld", key(i));
        assertEquals(88, requests.pendingCount(), "hello must also keep server-confirmed absences");
        requests.pump("minecraft:overworld", 0, sent::add);
        requests.pump("minecraft:overworld", 0, sent::add);
        assertEquals(600, sent.size());
    }

    @Test
    void responseCannotRemainHeldWhenWorldLookupFails() throws Exception {
        LodSectionRequests requests = get("sectionRequests");
        requests.request("minecraft:overworld", key(1));
        requests.pump("minecraft:overworld", 0, r -> true);
        // The JUnit process has no Minecraft singleton. Exercise the failure before
        // decoder setup, which used to leave the request held with no buffered data.
        assertDoesNotThrow(() -> this.handler.handleSectionData(new VoxyNetwork.SectionDataS2C(
                "minecraft:overworld", 0, 1, 0, 0, new byte[]{1})));
        assertEquals(0, requests.inFlightCount());
        assertEquals(1, requests.pendingCount());
        var sent = new ArrayList<LodSectionRequests.Request>();
        requests.pump("minecraft:overworld", System.nanoTime() + LodSectionRequests.RETRY_NANOS, sent::add);
        assertEquals(1, sent.size());
    }

    @Test
    void logoutClosesOldQueueAndResetsHandshakeMappingsAndDeferredPayloads() throws Exception {
        LodSectionRequests old = get("sectionRequests");
        old.request("minecraft:overworld", key(1));
        this.handler.handleHello(new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION));
        Map<String, int[]> blocks = get("serverBlockToClient");
        Map<String, int[]> biomes = get("serverBiomeToClient");
        Map<LodSectionRequests.Request, VoxyNetwork.SectionDataS2C> deferred = get("pendingSections");
        blocks.put("minecraft:overworld", new int[]{1});
        biomes.put("minecraft:overworld", new int[]{2});
        deferred.put(new LodSectionRequests.Request("minecraft:overworld", key(1)),
                new VoxyNetwork.SectionDataS2C("minecraft:overworld", 0, 1, 0, 0, new byte[]{1}));
        VoxyClientNetwork.onLoggingOut(null);
        assertFalse(VoxyClientNetwork.isServerAuthoritative());
        assertEquals(0L, (long) get("lastHelloNanos"));
        assertTrue(blocks.isEmpty());
        assertTrue(biomes.isEmpty());
        assertTrue(deferred.isEmpty());
        old.request("minecraft:overworld", key(2));
        assertEquals(0, old.pendingCount());
        LodSectionRequests current = get("sectionRequests");
        assertNotSame(old, current);
        current.request("minecraft:overworld", key(1));
        assertEquals(1, current.pendingCount());
    }

    private static long key(int x) { return WorldEngine.getWorldSectionId(0, x, 0, 0); }

    @SuppressWarnings("unchecked")
    private static <T> T get(String name) throws Exception {
        var field = VoxyClientNetwork.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    private static void set(String name, Object value) throws Exception {
        var field = VoxyClientNetwork.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
