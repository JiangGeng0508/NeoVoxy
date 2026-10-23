package me.cortex.voxy.client.compat.xaero;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client entry point. The bridge is resolved only after the optional mod/version gate. */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class XaeroSurfaceCompat {
    private static boolean checked, enabled, failed;
    private static volatile Throwable asynchronousFailure;

    /** May be called by Xaero's IO thread; detach the worker on the next client tick. */
    public static void reportFailure(Throwable failure) {
        if (asynchronousFailure == null) asynchronousFailure = failure;
    }

    public static boolean supplementalReadsAvailable() { return asynchronousFailure == null; }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!checked) {
            checked = true;
            enabled = XaeroGate.supported();
            if (!enabled && ModList.get().isLoaded("xaeroworldmap")) {
                Logger.warn("Xaero LOD map sync disabled: only World Map 1.47.0 (source format 6.8) is supported.");
            }
        }
        if (!enabled || failed) return;
        try {
            if (asynchronousFailure != null) throw new IllegalStateException("Xaero supplemental map IO failed", asynchronousFailure);
            XaeroSurfaceBridge.tick(VoxyConfig.CONFIG);
        }
        catch (Exception | LinkageError failure) {
            failed = true;
            XaeroSurfaceBridge.stop();
            Logger.error("Xaero LOD map sync disabled after an incompatible API or processing failure", failure);
        }
    }

    public static void stop() {
        if (enabled) XaeroSurfaceBridge.stop();
    }
    private XaeroSurfaceCompat() {}
}
