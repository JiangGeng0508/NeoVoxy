package me.cortex.voxy.client;

import me.cortex.voxy.commonImpl.network.LodViewDistance;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;
import java.util.function.Predicate;

/** Client-thread negotiation and rate limiting. Only successful sends advance the advertised view. */
final class LodClientView {
    static final long UPDATE_NANOS = 1_000_000_000L;
    private boolean negotiated;
    private String dimension;
    private int radius = LodViewDistance.MAX_BLOCKS;
    private long sentAt;

    boolean hello(boolean supportsV2, String dimension, int radius, long now, Predicate<CustomPacketPayload> sender) {
        if (!supportsV2) return sender.test(new VoxyNetwork.HelloC2S(VoxyNetwork.PROTOCOL_VERSION));
        if (dimension == null) return false; // Login can precede the client level; the tick retries.
        radius = LodViewDistance.clamp(radius);
        if (!sender.test(new VoxyNetwork.HelloV2C2S(VoxyNetwork.VIEW_PROTOCOL_VERSION, dimension, radius))) return false;
        this.remember(dimension, radius, now);
        return true;
    }

    void acknowledge(boolean v2) { this.negotiated = v2; }

    void update(String dimension, int radius, long now, Predicate<CustomPacketPayload> sender) {
        if (!this.negotiated) return;
        radius = LodViewDistance.clamp(radius);
        boolean changedWorld = !Objects.equals(this.dimension, dimension);
        if (!changedWorld && (this.radius == radius || now - this.sentAt < UPDATE_NANOS)) return;
        if (sender.test(new VoxyNetwork.ViewDistanceC2S(dimension, radius))) this.remember(dimension, radius, now);
    }

    private void remember(String dimension, int radius, long now) {
        this.dimension = dimension; this.radius = radius; this.sentAt = now;
    }

    boolean contains(LodSectionRequests.Request request, double x, double z) {
        return !this.negotiated || (Objects.equals(this.dimension, request.dimension())
                && LodViewDistance.contains(request.sectionKey(), x, z, this.radius));
    }

    void reset() {
        this.negotiated = false; this.dimension = null; this.radius = LodViewDistance.MAX_BLOCKS; this.sentAt = 0;
    }
}
