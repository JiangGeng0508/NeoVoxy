package me.cortex.voxy.client;

import me.cortex.voxy.compat.far.FarEntityProtocol;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class ServerCapabilities {
    private ServerCapabilities() {}

    public static boolean supports(CustomPacketPayload.Type<?>... channels) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return false;
        for (var channel : channels) {
            if (!connection.hasChannel(channel)) return false;
        }
        return true;
    }

    public static boolean farEntities() {
        return supports(FarEntityProtocol.HelloPayload.TYPE, FarEntityProtocol.PlayersPayload.TYPE);
    }

    public static boolean trains() {
        return supports(DistantTrainProtocol.CarriageShapePayload.TYPE, DistantTrainProtocol.TrainPosesPayload.TYPE);
    }

    public static boolean canConfigureFarEntities() {
        return Minecraft.getInstance().getConnection() == null || farEntities();
    }

    public static boolean canConfigureTrains() {
        return Minecraft.getInstance().getConnection() == null || trains();
    }
}
