package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.util.Map;

/** Probe only: remove the three optional registrations to exercise real V1 channel negotiation. */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class LegacyNetworkStress {
    @SuppressWarnings("unchecked")
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void register(RegisterPayloadHandlersEvent event) throws Exception {
        if (!Boolean.getBoolean("voxy.networkStressLegacy")) return;
        var field = NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS"); field.setAccessible(true);
        var registrations = (Map<ConnectionProtocol, Map<ResourceLocation, PayloadRegistration<?>>>) field.get(null);
        var play = registrations.get(ConnectionProtocol.PLAY);
        for (var type : java.util.List.of(VoxyNetwork.HelloV2C2S.TYPE, VoxyNetwork.HelloV2S2C.TYPE, VoxyNetwork.ViewDistanceC2S.TYPE)) {
            if (play.remove(type.id()) == null) throw new AssertionError("V2 registration was not removed: " + type.id());
        }
        System.out.println("NETWORK_STRESS legacy client exposes only V1 LOD channels");
    }
}
