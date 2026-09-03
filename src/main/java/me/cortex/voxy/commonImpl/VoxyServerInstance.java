package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Dedicated-server voxy instance: ingests chunks into a server-side LOD store and
 * streams serialized sections to voxy clients. Clients without the mod never send the
 * hello packet and are therefore never sent anything.
 */
public class VoxyServerInstance extends VoxyInstance {
    private static final int MAP_BATCH_SIZE = 128;
    private static final int MAX_OUTBOUND_PER_TICK = 32;

    private final MinecraftServer server;
    private final Path basePath;
    private final Config config;

    /** Players that announced voxy support via hello packet. */
    private final Set<java.util.UUID> voxyClients = ConcurrentHashMap.newKeySet();

    /** Engines that already have broadcast/mapping hooks attached. */
    private final ConcurrentHashMap<WorldIdentifier, WorldEngine> hookedEngines = new ConcurrentHashMap<>();

    /** Single FIFO outbound queue, preserves mapping-before-section causality. */
    private final ConcurrentLinkedDeque<Outbound> outbound = new ConcurrentLinkedDeque<>();

    private sealed interface Outbound permits MappingOutbound, SectionOutbound {}
    private record MappingOutbound(String dimension, VoxyNetwork.MappingDeltaS2C payload) implements Outbound {}
    private record SectionOutbound(String dimension, WorldIdentifier wid, long sectionKey, byte[] data) implements Outbound {}

    public VoxyServerInstance(MinecraftServer server) {
        super();
        this.server = server;
        this.basePath = server.getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath().normalize();
        this.config = StorageConfigUtil.getCreateStorageConfig(Config.class, c -> c.version == 1 && c.sectionStorageConfig != null,
                () -> DEFAULT_STORAGE_CONFIG, this.basePath);
        this.updateDedicatedThreads();
        Logger.info("Voxy server instance ready, storage: " + this.basePath);
    }

    public boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("voxy.serverIngest", "true"));
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        return this.isEnabled();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    /**
     * Gets or creates the engine for the identifier, attaching broadcast hooks once.
     * Hooks are (re)attached here rather than only on the server thread paths so an
     * engine recreated after an idle-free still broadcasts; attaching is idempotent
     * per engine instance.
     */
    public WorldEngine getOrCreateHooked(WorldIdentifier identifier, String dimension) {
        return this.getOrCreate(identifier);
    }

    @Override
    public WorldEngine getOrCreate(WorldIdentifier identifier, boolean incrementRef) {
        var engine = super.getOrCreate(identifier, incrementRef);
        if (engine != null) {
            this.hookEngine(identifier, engine);
        }
        return engine;
    }

    private void hookEngine(WorldIdentifier identifier, WorldEngine engine) {
        if (this.hookedEngines.put(identifier, engine) == engine) {
            return;//Same engine instance already hooked
        }
        final String dim = identifier.key.location().toString();
        final var eng = engine;
        final var wid = identifier;
        eng.setDirtyCallback((section, updateFlags, neighborMsk) -> this.onSectionDirty(dim, wid, eng, section));
        var mapper = eng.getMapper();
        mapper.setStateCallback(entry -> {
            if (!this.voxyClients.isEmpty()) this.outbound.add(new MappingOutbound(dim, VoxyNetwork.MappingDeltaS2C.block(entry.id, entry.serialize())));
        });
        mapper.setBiomeCallback(entry -> {
            if (!this.voxyClients.isEmpty()) this.outbound.add(new MappingOutbound(dim, VoxyNetwork.MappingDeltaS2C.biome(entry.id, entry.biome)));
        });
    }

    public WorldEngine getEngineForLevel(ServerLevel level) {
        var wid = WorldIdentifier.of(level);
        if (wid == null) {
            return null;
        }
        return this.getOrCreateHooked(wid, level.dimension().location().toString());
    }

    private void onSectionDirty(String dimension, WorldIdentifier wid, WorldEngine engine, WorldSection section) {
        if (!this.isRunning() || this.voxyClients.isEmpty()) {
            return;
        }
        //Runs on the ingest worker while the updater still holds the section: data is stable here.
        try {
            var serialized = SaveLoadSystem3.serialize(section);
            var buf = serialized.asByteBuffer();
            var bytes = new byte[buf.remaining()];
            buf.get(bytes);
            this.outbound.add(new SectionOutbound(dimension, wid, section.key, bytes));
        } catch (Exception e) {
            Logger.error("Voxy failed serializing dirty section for broadcast", e);
        }
    }

    // ---------- client tracking ----------

    public boolean isVoxyClient(java.util.UUID uuid) {
        return this.voxyClients.contains(uuid);
    }

    public void onClientHello(ServerPlayer player) {
        if (!this.isRunning()) {
            return;
        }
        boolean first = this.voxyClients.add(player.getUUID());
        VoxyNetwork.sendToPlayer(player, new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION));
        if (first) {
            Logger.info("Voxy client connected: " + player.getGameProfile().getName());
        }
        var level = player.serverLevel();
        var engine = this.getEngineForLevel(level);
        if (engine != null) {
            this.sendFullMappings(player, engine);
        }
    }

    public void onPlayerLogout(ServerPlayer player) {
        this.voxyClients.remove(player.getUUID());
    }

    /** Re-announces support and re-sends the mapping sync for the player's new dimension. */
    public void resyncPlayer(ServerPlayer player) {
        if (!this.isRunning() || !this.voxyClients.contains(player.getUUID())) {
            return;
        }
        VoxyNetwork.sendToPlayer(player, new VoxyNetwork.HelloS2C(VoxyNetwork.PROTOCOL_VERSION));
        var engine = this.getEngineForLevel(player.serverLevel());
        if (engine != null) {
            this.sendFullMappings(player, engine);
        }
    }

    private void sendFullMappings(ServerPlayer player, WorldEngine engine) {
        try {
            var states = engine.getMapper().getStateEntries();
            for (int start = 0; start < states.length; start += MAP_BATCH_SIZE) {
                var batch = new ArrayList<byte[]>(Math.min(MAP_BATCH_SIZE, states.length - start));
                for (int i = start; i < Math.min(states.length, start + MAP_BATCH_SIZE); i++) {
                    batch.add(states[i].serialize());
                }
                VoxyNetwork.sendToPlayer(player, new VoxyNetwork.BlockMapBatchS2C(start, batch));
            }
            var biomes = engine.getMapper().getBiomeEntries();
            for (int start = 0; start < biomes.length; start += MAP_BATCH_SIZE) {
                var batch = new ArrayList<String>(Math.min(MAP_BATCH_SIZE, biomes.length - start));
                for (int i = start; i < Math.min(biomes.length, start + MAP_BATCH_SIZE); i++) {
                    batch.add(biomes[i].biome);
                }
                VoxyNetwork.sendToPlayer(player, new VoxyNetwork.BiomeMapBatchS2C(start, batch));
            }
        } catch (Exception e) {
            Logger.error("Voxy failed sending mapping sync", e);
        }
    }

    // ---------- section requests (pull) ----------

    public void onSectionRequest(ServerPlayer player, VoxyNetwork.SectionRequestC2S request) {
        if (!this.isRunning() || !this.voxyClients.contains(player.getUUID())) {
            return;
        }
        ServerLevel level = null;
        for (var l : this.server.getAllLevels()) {
            if (l.dimension().location().toString().equals(request.dimension())) {
                level = l;
                break;
            }
        }
        if (level == null) {
            return;
        }
        var engine = this.getEngineForLevel(level);
        if (engine == null) {
            return;
        }
        byte[] data = null;
        var section = engine.acquireIfExists(request.lvl(), request.x(), request.y(), request.z());
        if (section != null) {
            try {
                var serialized = SaveLoadSystem3.serialize(section);
                var buf = serialized.asByteBuffer();
                data = new byte[buf.remaining()];
                buf.get(data);
            } catch (Exception e) {
                Logger.error("Voxy failed serializing requested section", e);
            } finally {
                section.release();
            }
        }
        VoxyNetwork.sendToPlayer(player, new VoxyNetwork.SectionDataS2C(
                request.dimension(), request.lvl(), request.x(), request.y(), request.z(),
                data == null ? new byte[0] : data));
    }

    // ---------- per-tick broadcast drain (server thread) ----------

    public void tick() {
        if (!this.isRunning()) {
            return;
        }
        int sent = 0;
        while (sent < MAX_OUTBOUND_PER_TICK) {
            var msg = this.outbound.poll();
            if (msg == null) {
                break;
            }
            if (msg instanceof MappingOutbound m) {
                this.broadcastInDimension(m.dimension, m.payload());
                sent++;
            } else if (msg instanceof SectionOutbound s) {
                this.broadcastInDimension(s.dimension, new VoxyNetwork.SectionDataS2C(
                        s.dimension,
                        WorldEngine.getLevel(s.sectionKey), WorldEngine.getX(s.sectionKey),
                        WorldEngine.getY(s.sectionKey), WorldEngine.getZ(s.sectionKey), s.data));
                sent++;
            }
        }
    }

    private void broadcastInDimension(String dimension, VoxyNetwork.SectionDataS2C sectionPayload) {
        broadcastInDimension(dimension, (VoxyNetwork.MappingDeltaS2C) null, sectionPayload);
    }

    private void broadcastInDimension(String dimension, VoxyNetwork.MappingDeltaS2C delta) {
        broadcastInDimension(dimension, delta, null);
    }

    private void broadcastInDimension(String dimension, VoxyNetwork.MappingDeltaS2C delta, VoxyNetwork.SectionDataS2C section) {
        for (var player : this.server.getPlayerList().getPlayers()) {
            if (!this.voxyClients.contains(player.getUUID())) {
                continue;
            }
            if (dimension != null && !player.serverLevel().dimension().location().toString().equals(dimension)) {
                continue;
            }
            if (delta != null) {
                VoxyNetwork.sendToPlayer(player, delta);
            } else if (section != null) {
                VoxyNetwork.sendToPlayer(player, section);
            }
        }
    }

    private static class Config {
        public int version = 1;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static final Config DEFAULT_STORAGE_CONFIG;
    static {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        DEFAULT_STORAGE_CONFIG = config;
    }
}
