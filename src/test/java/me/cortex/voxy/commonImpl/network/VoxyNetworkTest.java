package me.cortex.voxy.commonImpl.network;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class VoxyNetworkTest {
    @SuppressWarnings("unchecked")
    private static List<NegotiableNetworkComponent> lodChannels() throws ReflectiveOperationException {
        var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        field.setAccessible(true);
        var registrations = (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null);
        var play = registrations.get(ConnectionProtocol.PLAY);
        return List.of(VoxyNetwork.HelloC2S.TYPE, VoxyNetwork.HelloS2C.TYPE,
                        VoxyNetwork.SectionRequestC2S.TYPE, VoxyNetwork.SectionDataS2C.TYPE,
                        VoxyNetwork.BlockMapBatchS2C.TYPE, VoxyNetwork.BiomeMapBatchS2C.TYPE,
                        VoxyNetwork.MappingDeltaS2C.TYPE).stream()
                .map(type -> {
                    var registration = play.get(type.id());
                    assertNotNull(registration, type.id().toString());
                    return new NegotiableNetworkComponent(registration);
                }).toList();
    }

    @Test
    void clientCanJoinServerWithoutVoxy() throws ReflectiveOperationException {
        assertTrue(NetworkComponentNegotiator.negotiate(List.of(), lodChannels()).success());
    }

    @Test
    void serverAcceptsClientWithoutVoxy() throws ReflectiveOperationException {
        assertTrue(NetworkComponentNegotiator.negotiate(lodChannels(), List.of()).success());
    }

    @Test
    void matchingPeersNegotiateAllLodChannels() throws ReflectiveOperationException {
        var channels = lodChannels();
        var result = NetworkComponentNegotiator.negotiate(channels, channels);
        assertTrue(result.success());
        assertEquals(7, result.components().size());
    }

    @Test
    void staleConnectionCannotDeliverHelloOrTerrainAfterReconnect() throws Exception {
        var handlerField = VoxyNetwork.class.getDeclaredField("clientHandler");
        handlerField.setAccessible(true);
        var original = (VoxyNetwork.ClientHandler) handlerField.get(null);
        var oldConnection = new Connection(PacketFlow.CLIENTBOUND);
        var newConnection = new Connection(PacketFlow.CLIENTBOUND);
        var current = new AtomicReference<>(oldConnection);
        var delivered = new AtomicInteger();
        var handler = (VoxyNetwork.ClientHandler) Proxy.newProxyInstance(
                VoxyNetwork.ClientHandler.class.getClassLoader(), new Class[]{VoxyNetwork.ClientHandler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("acceptsConnection")) return args[0] == current.get();
                    delivered.incrementAndGet();
                    return null;
                });
        try {
            VoxyNetwork.setClientHandler(handler);
            VoxyNetwork.dispatchClient(oldConnection, h -> h.handleHello(new VoxyNetwork.HelloS2C(1)));
            assertEquals(1, delivered.get());
            current.set(newConnection);
            VoxyNetwork.dispatchClient(oldConnection, h -> h.handleHello(new VoxyNetwork.HelloS2C(1)));
            VoxyNetwork.dispatchClient(oldConnection, h -> h.handleSectionData(
                    new VoxyNetwork.SectionDataS2C("minecraft:overworld", 0, 0, 0, 0, new byte[0])));
            assertEquals(1, delivered.get());
            VoxyNetwork.dispatchClient(newConnection, h -> h.handleHello(new VoxyNetwork.HelloS2C(1)));
            assertEquals(2, delivered.get());
        } finally {
            VoxyNetwork.setClientHandler(original);
        }
    }
}
