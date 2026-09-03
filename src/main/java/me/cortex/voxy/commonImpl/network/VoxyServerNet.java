package me.cortex.voxy.commonImpl.network;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyServerInstance;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side entry points for voxy network packets. All work is deferred onto the
 * server thread; unknown/absent instances (integrated server) are ignored.
 */
public final class VoxyServerNet {
    private VoxyServerNet() {}

    private static VoxyServerInstance serverInstance() {
        var instance = VoxyCommon.getInstance();
        return instance instanceof VoxyServerInstance srv ? srv : null;
    }

    public static void onClientHello(IPayloadContext ctx, VoxyNetwork.HelloC2S payload) {
        if (payload.version() != VoxyNetwork.PROTOCOL_VERSION) {
            Logger.warn("Voxy client with incompatible protocol version: " + payload.version());
            return;
        }
        ctx.enqueueWork(() -> {
            var instance = serverInstance();
            if (instance == null) {
                return;
            }
            if (ctx.player() instanceof ServerPlayer player) {
                try {
                    instance.onClientHello(player);
                } catch (Exception e) {
                    Logger.error("Voxy failed handling client hello", e);
                }
            }
        });
    }

    public static void onSectionRequest(IPayloadContext ctx, VoxyNetwork.SectionRequestC2S payload) {
        ctx.enqueueWork(() -> {
            var instance = serverInstance();
            if (instance == null) {
                return;
            }
            if (ctx.player() instanceof ServerPlayer player) {
                try {
                    instance.onSectionRequest(player, payload);
                } catch (Exception e) {
                    Logger.error("Voxy failed handling section request", e);
                }
            }
        });
    }
}
