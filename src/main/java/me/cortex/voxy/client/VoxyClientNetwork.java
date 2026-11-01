package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/** Client-thread admission and confirmations; voxel and mapping work belongs to the connection's consumer. */
@EventBusSubscriber(modid = VoxyCommon.MOD_ID, value = Dist.CLIENT)
public final class VoxyClientNetwork implements VoxyNetwork.ClientHandler {
    private VoxyClientNetwork() {}

    private static volatile boolean serverAuthoritative;
    private static volatile LodSectionRequests sectionRequests = new LodSectionRequests();
    private static LodClientIngestion ingestion;
    private static VoxyInstance ingestionOwner;
    private static long generation;
    private static volatile long lastHelloNanos;
    private static final LodClientView view = new LodClientView();

    public static boolean isServerAuthoritative() { return serverAuthoritative; }
    public static void init() { VoxyNetwork.setClientHandler(new VoxyClientNetwork()); }

    @Override public boolean acceptsConnection(Connection connection) {
        var current = Minecraft.getInstance().getConnection();
        return current != null && current.getConnection() == connection && connection.isConnected();
    }

    @SubscribeEvent public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) { sendHello(); }

    private static void sendHello() {
        if (Minecraft.getInstance().hasSingleplayerServer() || !ServerCapabilities.lodSync() || !VoxyCommon.isAvailable()) return;
        var level = Minecraft.getInstance().level;
        long now = System.nanoTime();
        if (view.hello(ServerCapabilities.lodViewDistance(), level == null ? null : level.dimension().location().toString(),
                VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks(), now, VoxyClientNetwork::sendControl)) lastHelloNanos = now;
    }

    private static boolean sendControl(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        try { VoxyNetwork.sendToServer(payload); return true; }
        catch (Exception e) { Logger.error("Voxy failed sending view negotiation", e); return false; }
    }

    @SubscribeEvent public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        serverAuthoritative = false; lastHelloNanos = 0; generation++;
        view.reset();
        sectionRequests.close();
        stopIngestion(ingestionOwner);
        sectionRequests = new LodSectionRequests();
    }

    /** Called before the instance releases worlds, including configuration-driven recreation. */
    static void stopIngestion(VoxyInstance owner) {
        if (ingestion == null || ingestionOwner != owner) return;
        var previous = ingestion; ingestion = null; ingestionOwner = null;
        var pending = previous.sectionKeys();
        previous.close();
        for (var request : pending) sectionRequests.retry(request, System.nanoTime());
        // Fresh engines must import mappings even if the connection itself survived recreation.
        lastHelloNanos = 0;
    }

    private static LodClientIngestion consumer() {
        if (ingestion != null) return ingestion;
        var instance = VoxyCommon.getInstance();
        if (instance == null || !instance.isRunning()) return null;
        var requests = sectionRequests;
        long session = generation;
        ingestionOwner = instance;
        ingestion = new LodClientIngestion(instance.getServiceManager(), dimension -> {
            var mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || !mc.level.dimension().location().toString().equals(dimension)) return null;
            var identifier = WorldIdentifier.of(mc.level);
            return identifier == null ? null : instance.getOrCreate(identifier);
        }, new LodClientIngestion.Confirmations() {
            public void section(LodSectionRequests.Request request, LodClientIngestion.Result result) {
                if (session != generation || requests != sectionRequests || ingestionOwner != instance) return;
                if (result == LodClientIngestion.Result.APPLIED || result == LodClientIngestion.Result.MISSING)
                    requests.complete(request, result == LodClientIngestion.Result.MISSING);
                else requests.retry(request, System.nanoTime());
            }
            public void mappings(String dimension, LodClientIngestion.Snapshot snapshot) {
                // The consumer publishes immutable tables while draining on this client thread.
            }
        });
        return ingestion;
    }

    static SectionSerializationStorage.ISectionMissListener missListener(String dimension) {
        var requests = sectionRequests;
        return sectionKey -> {
            var minecraft = Minecraft.getInstance();
            if (requests == sectionRequests && (minecraft == null || (!minecraft.hasSingleplayerServer()
                    && (minecraft.getConnection() == null || ServerCapabilities.lodSync())))) requests.request(dimension, sectionKey);
        };
    }

    @SubscribeEvent public static void onClientTick(ClientTickEvent.Post event) {
        // Confirmations can release ownership even while rendering/configuration is disabled.
        if (ingestion != null) ingestion.drain(LodClientIngestion.CONFIRM_NANOS);
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null || minecraft.hasSingleplayerServer() || !VoxyCommon.isAvailable()
                || !VoxyConfig.CONFIG.enabled || !ServerCapabilities.lodSync()) return;
        long now = System.nanoTime();
        view.update(level.dimension().location().toString(), VoxyConfig.CONFIG.getFarEntityRenderDistanceBlocks(),
                now, VoxyClientNetwork::sendControl);
        if (!serverAuthoritative || ingestion == null || ingestion.needsMappings()) {
            if (now - lastHelloNanos >= LodSectionRequests.RETRY_NANOS) sendHello();
        }
        if (serverAuthoritative && minecraft.player != null && (ingestion == null || !ingestion.pressured()))
            sectionRequests.pump(level.dimension().location().toString(), now,
                    request -> view.contains(request, minecraft.player.getX(), minecraft.player.getZ()),
                    VoxyClientNetwork::sendSectionRequest);
    }

    private static boolean sendSectionRequest(LodSectionRequests.Request request) {
        long key = request.sectionKey();
        try {
            VoxyNetwork.sendToServer(new VoxyNetwork.SectionRequestC2S(request.dimension(), WorldEngine.getLevel(key),
                    WorldEngine.getX(key), WorldEngine.getY(key), WorldEngine.getZ(key)));
            return true;
        } catch (Exception e) { Logger.error("Voxy failed requesting section from server", e); return false; }
    }

    @Override public void handleHello(VoxyNetwork.HelloS2C payload) {
        if (payload.version() != VoxyNetwork.PROTOCOL_VERSION) {
            Logger.warn("Voxy server uses incompatible protocol version: " + payload.version()); return;
        }
        serverAuthoritative = true;
        view.acknowledge(false);
        Logger.info("Voxy server sync enabled, local ingest suspended");
    }

    @Override public void handleHelloV2(VoxyNetwork.HelloV2S2C payload) {
        if (payload.version() != VoxyNetwork.VIEW_PROTOCOL_VERSION) return;
        serverAuthoritative = true;
        view.acknowledge(true);
        Logger.info("Voxy V2 server sync enabled with client LoD view distance");
    }

    private static void acceptMapping(Object payload) {
        var minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return;
        var consumer = consumer();
        if (consumer != null) consumer.mapping(minecraft.level.dimension().location().toString(), payload);
    }
    @Override public void handleBlockMaps(VoxyNetwork.BlockMapBatchS2C payload) { acceptMapping(payload); }
    @Override public void handleBiomeMaps(VoxyNetwork.BiomeMapBatchS2C payload) { acceptMapping(payload); }
    @Override public void handleMappingDelta(VoxyNetwork.MappingDeltaS2C payload) {
        acceptMapping(payload.isBlock() ? new VoxyNetwork.BlockMapBatchS2C(payload.id(), List.of(payload.data()))
                : new VoxyNetwork.BiomeMapBatchS2C(payload.id(), List.of(payload.biomeString())));
    }

    @Override public void handleSectionData(VoxyNetwork.SectionDataS2C payload) {
        if (payload.lvl() < 0 || payload.lvl() > WorldEngine.MAX_LOD_LAYER) return;
        long key = WorldEngine.getWorldSectionId(payload.lvl(), payload.x(), payload.y(), payload.z());
        if (WorldEngine.getX(key) != payload.x() || WorldEngine.getY(key) != payload.y() || WorldEngine.getZ(key) != payload.z()) return;
        var request = new LodSectionRequests.Request(payload.dimension(), key);
        sectionRequests.hold(request); // Also track broadcasts rejected by admission.
        try {
            var consumer = consumer();
            if (consumer != null && consumer.section(payload, request)) return;
        } catch (Exception e) { Logger.error("Voxy failed admitting server terrain", e); }
        sectionRequests.retry(request, System.nanoTime());
    }

    public static LodClientIngestion.Stats ingestionStats() { return ingestion == null ? null : ingestion.stats(); }
}
