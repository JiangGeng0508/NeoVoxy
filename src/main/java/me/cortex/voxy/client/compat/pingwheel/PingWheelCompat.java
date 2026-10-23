package me.cortex.voxy.client.compat.pingwheel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Keeps optional API resolution behind the gate, including shutdown without PingWheel. */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class PingWheelCompat {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (PingWheelGate.supported()) PingWheelBridge.tick();
    }
    public static void stop() {
        if (PingWheelGate.supported()) PingWheelBridge.invalidate();
    }
    private PingWheelCompat() {}
}
