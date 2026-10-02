package me.cortex.voxy.commonImpl.network;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.negotiation.NegotiableNetworkComponent;
import net.neoforged.neoforge.network.negotiation.NetworkComponentNegotiator;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
}
