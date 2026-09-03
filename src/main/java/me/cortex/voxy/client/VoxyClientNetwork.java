package me.cortex.voxy.client;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
    private static final List<VoxyNetwork.SectionDataS2C> pendingSections = new ArrayList<>();
    private static final Set<String> inFlightRequests = ConcurrentHashMap.newKeySet();
    private static final int MAX_IN_FLIGHT = 512;
    private static volatile long lastHelloNanos;

    public static boolean isServerAuthoritative() {
        return serverAuthoritative;
    }

    public static void init() {
        VoxyNetwork.setClientHandler(new VoxyClientNetwork());
        SectionSerializationStorage.MISS_LISTENER = VoxyClientNetwork::onStoreMiss;
    }

    // ---------- connection lifecycle ----------

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        sendHello();
    }

    private static void sendHello() {
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
        synchronized (pendingSections) {
            pendingSections.clear();
        }
        inFlightRequests.clear();
        serverBlockToClient.clear();
        serverBiomeToClient.clear();
    }

    // ---------- store miss -> pull ----------

    private static void onStoreMiss(long sectionKey) {
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) {
            return;
        }
        if (!serverAuthoritative) {
            //Hello may have been lost (or the server started voxy later); retry it
            // throttled so a mod-less server is not spammed with unknown packets
            if (System.nanoTime() - lastHelloNanos > 5_000_000_000L) {
                sendHello();
            }
            return;
        }
        //Only pull for the dimension the player is currently in
        String dim = level.dimension().location().toString();
        String reqKey = dim + "#" + sectionKey;
        if (inFlightRequests.size() > MAX_IN_FLIGHT) {
            return;
        }
        if (!inFlightRequests.add(reqKey)) {
            return;
        }
        try {
            VoxyNetwork.sendToServer(new VoxyNetwork.SectionRequestC2S(dim,
                    WorldEngine.getLevel(sectionKey), WorldEngine.getX(sectionKey),
                    WorldEngine.getY(sectionKey), WorldEngine.getZ(sectionKey)));
        } catch (Exception e) {
            Logger.error("Voxy failed requesting section from server", e);
            inFlightRequests.remove(reqKey);
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
        inFlightRequests.clear();
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
                map[serverId] = mapper.getIdForBlockState(entry.state);
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
        inFlightRequests.remove(payload.dimension() + "#" + WorldEngine.getWorldSectionId(
                payload.lvl(), payload.x(), payload.y(), payload.z()));
        if (payload.data().length == 0) {
            return;//Server has no data for this section
        }
        if (!injectSection(payload)) {
            synchronized (pendingSections) {
                if (pendingSections.size() < 1024) {
                    pendingSections.add(payload);
                }
            }
        }
    }

    private static void retryPending() {
        synchronized (pendingSections) {
            if (pendingSections.isEmpty()) {
                return;
            }
            var retry = new ArrayList<>(pendingSections);
            pendingSections.clear();
            for (var payload : retry) {
                if (!injectSection(payload) && pendingSections.size() < 1024) {
                    pendingSections.add(payload);
                }
            }
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

    /**
     * Translates server-local ids to local ids and writes the section into the local engine.
     * Returns false when server mappings needed for translation have not arrived yet.
     */
    private static boolean injectSection(VoxyNetwork.SectionDataS2C payload) {
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> handleSectionDataStatic(payload));
            return true;
        }
        var level = minecraft.level;
        if (level == null || !level.dimension().location().toString().equals(payload.dimension())) {
            return true;//Stale section for another dimension, drop
        }
        var wid = WorldIdentifier.of(level);
        if (wid == null) {
            return true;
        }
        var engine = wid.getOrCreateEngine();
        if (engine == null || !engine.isLive()) {
            return true;
        }

        long expectedKey = WorldEngine.getWorldSectionId(payload.lvl(), payload.x(), payload.y(), payload.z());
        byte[] data = payload.data();
        if (data.length < 16 + 32 * 32 * 32 * 2) {
            Logger.error("Voxy got truncated section from server");
            return true;
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
                return true;
            }
            long metadata = MemoryUtil.memGetLong(base + 8);
            int lutCount = (int) (metadata & 0xFFFF);
            byte childMask = (byte) ((metadata >>> 16) & 0xFF);
            if (data.length != 16 + 32 * 32 * 32 * 2 + (long) lutCount * 8) {
                Logger.error("Voxy got malformed section from server");
                return true;
            }
            long shortsBase = base + 16;
            long lutBase = shortsBase + 32 * 32 * 32 * 2L;

            //Translate the server LUT to local ids first so we never write partial data
            int[] blockMap = serverBlockToClient.get(payload.dimension());
            int[] biomeMap = serverBiomeToClient.get(payload.dimension());
            long[] translated = new long[lutCount];
            for (int i = 0; i < lutCount; i++) {
                long serverLong = MemoryUtil.memGetLong(lutBase + (long) i * 8);
                int sBlock = (int) ((serverLong >>> 27) & ((1 << 20) - 1));
                if (sBlock == 0) {
                    translated[i] = serverLong;//Air carries no mapping, light bits pass through
                    continue;
                }
                int sBiome = (int) ((serverLong >>> 47) & 0x1FF);
                int light = (int) ((serverLong >>> 56) & 0xFF);
                if (blockMap == null || sBlock >= blockMap.length || blockMap[sBlock] < 0
                        || biomeMap == null || sBiome >= biomeMap.length || biomeMap[sBiome] < 0) {
                    return false;//Mappings not yet synced, retry later
                }
                translated[i] = (Integer.toUnsignedLong(light) << 56)
                        | (Integer.toUnsignedLong(biomeMap[sBiome]) << 47)
                        | (Integer.toUnsignedLong(blockMap[sBlock]) << 27);
            }

            var section = engine.acquire(payload.lvl(), payload.x(), payload.y(), payload.z());
            try {
                long[] raw = section._unsafeGetRawDataArray();
                for (int i = 0; i < 32 * 32 * 32; i++) {
                    raw[i] = translated[Short.toUnsignedInt(MemoryUtil.memGetShort(shortsBase + (long) i * 2))];
                }
                section._unsafeSetNonEmptyChildren(childMask);
                if (payload.lvl() == 0) {
                    int empty = 0;
                    for (long v : raw) {
                        if (Mapper.isAir(v)) empty++;
                    }
                    section.addNonEmptyBlockCount((32 * 32 * 32 - empty) - section.getNonEmptyBlockCount());
                }
                engine.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS, 0);
            } finally {
                section.release();
            }
            if (engine.storage instanceof SectionSerializationStorage serializer) {
                serializer.forgetMissingSection(section.key);
            }
            engine.markActive();
            return true;
        } catch (Exception e) {
            Logger.error("Voxy failed injecting server section", e);
            return true;
        } finally {
            if (direct != null) {
                MemoryUtil.memFree(direct);
            }
        }
    }

    private static void handleSectionDataStatic(VoxyNetwork.SectionDataS2C payload) {
        //Re-entry from another thread via minecraft.execute: instance dispatch not needed, call inject directly
        if (!injectSection(payload)) {
            synchronized (pendingSections) {
                if (pendingSections.size() < 1024) {
                    pendingSections.add(payload);
                }
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
