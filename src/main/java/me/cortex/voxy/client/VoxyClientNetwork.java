package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import me.cortex.voxy.commonImpl.network.LodMappingTranslator;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the voxy server LOD sync.
 *
 * <p>When the server answers our hello, the connection becomes server-authoritative:
 * local ingest is disabled and sections are pulled from the server on store misses,
 * then translated from server-local mapping ids to local ids and injected into the
 * local world engine (which also persists them to the local cache).
 */
@EventBusSubscriber(modid = VoxyCommon.MOD_ID, value = Dist.CLIENT)
public final class VoxyClientNetwork implements VoxyNetwork.ClientHandler {
    private VoxyClientNetwork() {}

    private static volatile boolean serverAuthoritative;
    /** Server-local -> client-local id translation, isolated per dimension. Main thread only. */
    private static final java.util.Map<String, int[]> serverBlockToClient = new ConcurrentHashMap<>();
    private static final java.util.Map<String, int[]> serverBiomeToClient = new ConcurrentHashMap<>();
    private static final Map<LodSectionRequests.Request, VoxyNetwork.SectionDataS2C> pendingSections = new LinkedHashMap<>();
    private static final int MAX_PENDING_PAYLOADS = 1024;
    private static volatile LodSectionRequests sectionRequests = new LodSectionRequests();
    private static volatile long lastHelloNanos;

    public static boolean isServerAuthoritative() {
        return serverAuthoritative;
    }

    public static void init() {
        VoxyNetwork.setClientHandler(new VoxyClientNetwork());
    }

    @Override
    public boolean acceptsConnection(Connection connection) {
        var current = Minecraft.getInstance().getConnection();
        return current != null && current.getConnection() == connection && connection.isConnected();
    }

    // ---------- connection lifecycle ----------

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendHello();
    }

    private static void sendHello() {
        if (Minecraft.getInstance().hasSingleplayerServer() || !ServerCapabilities.lodSync() || !VoxyCommon.isAvailable()) {
            return;
        }
        lastHelloNanos = System.nanoTime();
        try {
            VoxyNetwork.sendToServer(new VoxyNetwork.HelloC2S(VoxyNetwork.PROTOCOL_VERSION));
        } catch (Exception e) {
            Logger.error("Voxy failed sending hello to server", e);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        serverAuthoritative = false;
        lastHelloNanos = 0;
        sectionRequests.close();
        sectionRequests = new LodSectionRequests();
        pendingSections.clear();
        serverBlockToClient.clear();
        serverBiomeToClient.clear();
    }

    // ---------- store miss -> pull ----------

    static SectionSerializationStorage.ISectionMissListener missListener(String dimension) {
        var requests = sectionRequests;
        // Bind the dimension at storage creation, not by reading the current world on a
        // storage worker. Minecraft may not expose its connection yet during world/store
        // creation: check availability when notified, rather than permanently omitting
        // the listener. Old stores/workers retain only their closed session queue.
        return sectionKey -> {
            // Integrated servers register these channels but have no VoxyServerInstance.
            var minecraft = Minecraft.getInstance();
            if (requests == sectionRequests && (minecraft == null
                    || (!minecraft.hasSingleplayerServer()
                        && (minecraft.getConnection() == null || ServerCapabilities.lodSync())))) {
                requests.request(dimension, sectionKey);
            }
        };
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || Minecraft.getInstance().hasSingleplayerServer()
                || !VoxyCommon.isAvailable() || !VoxyConfig.CONFIG.enabled || !ServerCapabilities.lodSync()) return;
        long now = System.nanoTime();
        // Both handshake and mapping recovery must progress even if the local negative
        // cache (or the active-section cache) prevents further store misses.
        if (!serverAuthoritative || !pendingSections.isEmpty()) {
            if (now - lastHelloNanos >= LodSectionRequests.RETRY_NANOS) {
                sendHello();
            }
        }
        if (serverAuthoritative && pendingSections.size() < MAX_PENDING_PAYLOADS) {
            sectionRequests.pump(level.dimension().location().toString(), now, VoxyClientNetwork::sendSectionRequest);
        }
    }

    private static boolean sendSectionRequest(LodSectionRequests.Request request) {
        long sectionKey = request.sectionKey();
        try {
            VoxyNetwork.sendToServer(new VoxyNetwork.SectionRequestC2S(request.dimension(),
                    WorldEngine.getLevel(sectionKey), WorldEngine.getX(sectionKey),
                    WorldEngine.getY(sectionKey), WorldEngine.getZ(sectionKey)));
            return true;
        } catch (Exception e) {
            Logger.error("Voxy failed requesting section from server", e);
            return false;
        }
    }

    // ---------- payload handling (client main thread) ----------

    @Override
    public void handleHello(VoxyNetwork.HelloS2C payload) {
        if (payload.version() != VoxyNetwork.PROTOCOL_VERSION) {
            Logger.warn("Voxy server uses incompatible protocol version: " + payload.version());
            return;
        }
        serverAuthoritative = true;
        //NOTE: translation maps are per-dimension and intentionally kept: a hello is also
        // sent on dimension resync, and other dimensions' maps stay valid. Pending sections
        // are retried instead of dropped so untranslatable ones survive a resync.
        retryPending();
        Logger.info("Voxy server sync enabled, local ingest suspended");
    }

    @Override
    public void handleBlockMaps(VoxyNetwork.BlockMapBatchS2C payload) {
        //Batches are sent per-dimension right after hello/resync while the player is in
        // that dimension, so the current level's dimension owns them
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            return;
        }
        applyBlockMaps(level.dimension().location().toString(), payload.startId(), payload.entries());
        retryPending();
    }

    @Override
    public void handleBiomeMaps(VoxyNetwork.BiomeMapBatchS2C payload) {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            return;
        }
        applyBiomeMaps(level.dimension().location().toString(), payload.startId(), payload.entries());
        retryPending();
    }

    @Override
    public void handleMappingDelta(VoxyNetwork.MappingDeltaS2C payload) {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            return;
        }
        String dim = level.dimension().location().toString();
        if (payload.isBlock()) {
            applyBlockMaps(dim, payload.id(), List.of(payload.data()));
        } else {
            applyBiomeMaps(dim, payload.id(), List.of(payload.biomeString()));
        }
        retryPending();
    }

    private static void applyBlockMaps(String dim, int startId, List<byte[]> entries) {
        var mapper = clientMapper();
        if (mapper == null) {
            return;
        }
        var map = blockMap(dim);
        for (int i = 0; i < entries.size(); i++) {
            int serverId = startId + i;
            try {
                var entry = Mapper.StateEntry.deserialize(serverId, entries.get(i), new boolean[1]);
                map = grow(map, serverId);
                serverBlockToClient.put(dim, map);
                map[serverId] = mapper.importStateEntry(entry);
            } catch (Exception e) {
                Logger.error("Voxy failed applying server block mapping " + serverId, e);
            }
        }
    }

    private static void applyBiomeMaps(String dim, int startId, List<String> entries) {
        var mapper = clientMapper();
        if (mapper == null) {
            return;
        }
        var map = biomeMap(dim);
        for (int i = 0; i < entries.size(); i++) {
            int serverId = startId + i;
            try {
                map = grow(map, serverId);
                serverBiomeToClient.put(dim, map);
                map[serverId] = mapper.getOrCreateBiomeId(entries.get(i));
            } catch (Exception e) {
                Logger.error("Voxy failed applying server biome mapping " + serverId, e);
            }
        }
    }

    @Override
    public void handleSectionData(VoxyNetwork.SectionDataS2C payload) {
        acceptSectionData(payload);
    }

    private static void acceptSectionData(VoxyNetwork.SectionDataS2C payload) {
        var request = new LodSectionRequests.Request(payload.dimension(), WorldEngine.getWorldSectionId(
                payload.lvl(), payload.x(), payload.y(), payload.z()));
        pendingSections.remove(request);
        if (payload.data().length == 0) {
            sectionRequests.complete(request, true);
            return;//Server has no data for this section
        }
        sectionRequests.hold(request);
        InjectionResult result;
        try {
            result = injectSection(payload);
        } catch (Exception e) {
            // World lookup/creation may fail before the decoder acquires its buffers.
            // Do not leave this request held forever without a retained payload.
            Logger.error("Voxy failed preparing server section injection", e);
            result = InjectionResult.RETRY;
        }
        switch (result) {
            case APPLIED -> sectionRequests.complete(request, false);
            case WAIT_FOR_MAPPINGS -> {
                if (pendingSections.size() < MAX_PENDING_PAYLOADS) {
                    pendingSections.put(request, payload);
                } else {
                    // Retain the key when the payload buffer fills; pull again after
                    // mappings arrive instead of losing it behind the local miss cache.
                    sectionRequests.retry(request, System.nanoTime());
                }
            }
            case RETRY -> sectionRequests.retry(request, System.nanoTime());
        }
    }

    private static void retryPending() {
        if (pendingSections.isEmpty()) return;
        var retry = new ArrayList<>(pendingSections.values());
        pendingSections.clear();
        for (var payload : retry) {
            acceptSectionData(payload);
        }
    }

    private static Mapper clientMapper() {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            return null;
        }
        var wid = WorldIdentifier.of(level);
        if (wid == null) {
            return null;
        }
        var engine = wid.getOrCreateEngine();
        return engine == null ? null : engine.getMapper();
    }

    private enum InjectionResult { APPLIED, WAIT_FOR_MAPPINGS, RETRY }

    /**
     * Translates server-local ids to local ids and writes the section into the local engine.
     * Distinguishes successful application, missing mappings and transient failures.
     */
    private static InjectionResult injectSection(VoxyNetwork.SectionDataS2C payload) {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null || !level.dimension().location().toString().equals(payload.dimension())) {
            return InjectionResult.RETRY;//Pull again when this dimension becomes active.
        }
        var wid = WorldIdentifier.of(level);
        if (wid == null) {
            return InjectionResult.RETRY;
        }
        var engine = wid.getOrCreateEngine();
        if (engine == null || !engine.isLive()) {
            return InjectionResult.RETRY;
        }

        long expectedKey = WorldEngine.getWorldSectionId(payload.lvl(), payload.x(), payload.y(), payload.z());
        byte[] data = payload.data();
        if (data.length < 16 + 32 * 32 * 32 * 2) {
            Logger.error("Voxy got truncated section from server");
            return InjectionResult.RETRY;
        }

        ByteBuffer direct = null;
        try {
            direct = MemoryUtil.memAlloc(data.length);
            direct.put(data);
            direct.flip();
            long base = MemoryUtil.memAddress(direct);

            long key = MemoryUtil.memGetLong(base);
            if (key != expectedKey) {
                Logger.error("Voxy server section key mismatch, dropping");
                return InjectionResult.RETRY;
            }
            long metadata = MemoryUtil.memGetLong(base + 8);
            int lutCount = (int) (metadata & 0xFFFF);
            byte childMask = (byte) ((metadata >>> 16) & 0xFF);
            if (lutCount == 0 || data.length != 16 + 32 * 32 * 32 * 2 + (long) lutCount * 8) {
                Logger.error("Voxy got malformed section from server");
                return InjectionResult.RETRY;
            }
            long shortsBase = base + 16;
            long lutBase = shortsBase + 32 * 32 * 32 * 2L;

            //Translate the server LUT to local ids first so we never write partial data
            int[] blockMap = serverBlockToClient.get(payload.dimension());
            int[] biomeMap = serverBiomeToClient.get(payload.dimension());
            long[] translated = new long[lutCount];
            for (int i = 0; i < lutCount; i++) {
                long serverLong = MemoryUtil.memGetLong(lutBase + (long) i * 8);
                translated[i] = LodMappingTranslator.translate(serverLong, blockMap, biomeMap);
                if (translated[i] == Mapper.UNKNOWN_MAPPING) {
                    return InjectionResult.WAIT_FOR_MAPPINGS;
                }
            }

            // Validate before touching the live section. A malformed response must not
            // publish a partially written section or be acknowledged as complete.
            for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
                if (Short.toUnsignedInt(MemoryUtil.memGetShort(shortsBase + (long) i * 2)) >= lutCount) {
                    Logger.error("Voxy got invalid section palette index from server");
                    return InjectionResult.RETRY;
                }
            }

            var section = engine.acquire(payload.lvl(), payload.x(), payload.y(), payload.z());
            try {
                synchronized (section) {
                    long[] raw = section._unsafeGetRawDataArray();
                    for (int i = 0; i < 32 * 32 * 32; i++) {
                        raw[i] = translated[Short.toUnsignedInt(MemoryUtil.memGetShort(shortsBase + (long) i * 2))];
                    }
                    section._unsafeSetNonEmptyChildren(childMask);
                    section.setKnownOctants(me.cortex.voxy.common.world.SaveLoadSystem3.decodeKnownOctants(metadata));
                    if (payload.lvl() == 0) {
                        int empty = 0;
                        for (long v : raw) {
                            if (Mapper.isAir(v)) empty++;
                        }
                        section.addNonEmptyBlockCount((32 * 32 * 32 - empty) - section.getNonEmptyBlockCount());
                    }
                }
                engine.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS, 0);
            } finally {
                section.release();
            }
            if (engine.storage instanceof SectionSerializationStorage serializer) {
                serializer.forgetMissingSection(section.key);
            }
            engine.markActive();
            return InjectionResult.APPLIED;
        } catch (Exception e) {
            Logger.error("Voxy failed injecting server section", e);
            return InjectionResult.RETRY;
        } finally {
            if (direct != null) {
                MemoryUtil.memFree(direct);
            }
        }
    }

    private static int[] blockMap(String dim) {
        return serverBlockToClient.computeIfAbsent(dim, k -> filled(512));
    }

    private static int[] biomeMap(String dim) {
        return serverBiomeToClient.computeIfAbsent(dim, k -> filled(64));
    }

    private static int[] filled(int size) {
        var arr = new int[size];
        Arrays.fill(arr, -1);
        return arr;
    }

    private static int[] grow(int[] map, int id) {
        if (id < map.length) {
            return map;
        }
        int next = Math.max(id + 1, map.length * 2);
        var grown = new int[next];
        Arrays.fill(grown, -1);
        System.arraycopy(map, 0, grown, 0, map.length);
        return grown;
    }
}
