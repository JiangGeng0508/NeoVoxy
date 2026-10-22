package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.network.VoxyNetwork;
import me.cortex.voxy.commonImpl.network.LodServerScheduler;
import me.cortex.voxy.commonImpl.network.LodUpdateQueue;
import me.cortex.voxy.commonImpl.network.LodUpdateRecovery;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Dedicated-server voxy instance: ingests chunks into a server-side LOD store and
 * streams serialized sections to voxy clients. Clients without the mod never send the
 * hello packet and are therefore never sent anything.
 */
public class VoxyServerInstance extends VoxyInstance {
    private static final int MAP_BATCH_SIZE = 128;

    private final MinecraftServer server;
    private final Path basePath;
    private final Config config;

    /** Players that announced voxy support via hello packet. */
    private final Set<java.util.UUID> voxyClients = ConcurrentHashMap.newKeySet();

    /** Engines that already have broadcast/mapping hooks attached. */
    private final ConcurrentHashMap<WorldIdentifier, WorldEngine> hookedEngines = new ConcurrentHashMap<>();

    private final LodServerScheduler scheduler = new LodServerScheduler();
    private Thread recovery;
    private int recoveryCooldown;
    private volatile long recoverySweeps, recoverySpoolBytes;
    private volatile boolean networkClosed;

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
        engine.setDirtyCallback((section, flags, neighbors) -> {
            if (!this.networkClosed && this.isRunning()) {
                this.scheduler.dirty(new LodUpdateQueue.Key(dim, section.key));
            }
        });
    }

    public WorldEngine getEngineForLevel(ServerLevel level) {
        var wid = WorldIdentifier.of(level);
        if (wid == null) {
            return null;
        }
        return this.getOrCreateHooked(wid, level.dimension().location().toString());
    }

    private static LodServerScheduler.View view(ServerPlayer player) {
        return new LodServerScheduler.View(player.getUUID(), player.serverLevel().dimension().location().toString(),
                player.getX(), player.getZ());
    }

    public boolean isVoxyClient(UUID uuid) {
        return this.voxyClients.contains(uuid);
    }

    public void onClientHello(ServerPlayer player) {
        if (!this.isRunning() || this.networkClosed) return;
        if (this.voxyClients.add(player.getUUID())) {
            Logger.info("Voxy client connected: " + player.getGameProfile().getName());
        }
        this.scheduler.hello(view(player));
    }

    public void onPlayerLogout(ServerPlayer player) {
        this.voxyClients.remove(player.getUUID());
        this.scheduler.logout(player.getUUID());
    }

    public void resyncPlayer(ServerPlayer player) {
        if (this.isRunning() && this.voxyClients.contains(player.getUUID())) {
            this.scheduler.hello(view(player));
        }
    }

    public void onSectionRequest(ServerPlayer player, VoxyNetwork.SectionRequestC2S request) {
        if (!this.isRunning() || this.networkClosed) return;
        this.scheduler.updateView(view(player));
        this.scheduler.request(player.getUUID(), request);
    }

    private final LodServerScheduler.Transport transport = new LodServerScheduler.Transport() {
        @Override
        public boolean writable(UUID id) {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) return false;
            var connection = player.connection.getConnection();
            return connection.isConnected() && connection.channel() != null && connection.channel().isWritable();
        }

        @Override
        public LodServerScheduler.Mappings nextMappings(UUID id, int blockCursor, int biomeCursor, int credit) {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) throw new IllegalStateException("Player disconnected");
            var engine = getEngineForLevel(player.serverLevel());
            if (engine == null) throw new IllegalStateException("Missing world engine");
            var states = engine.getMapper().getStateEntries(blockCursor, MAP_BATCH_SIZE);
            if (states.length != 0) {
                var entries = new ArrayList<byte[]>();
                for (var state : states) {
                    entries.add(state.serialize());
                    // Keep an oversized first entry pending; never treat it as completed sync.
                    if (VoxyNetwork.encodedSize(new VoxyNetwork.BlockMapBatchS2C(blockCursor, entries)) > credit) {
                        if (entries.size() > 1) entries.removeLast();
                        break;
                    }
                }
                return new LodServerScheduler.Mappings(new VoxyNetwork.BlockMapBatchS2C(blockCursor, entries),
                        blockCursor + entries.size(), biomeCursor);
            }
            var biomes = engine.getMapper().getBiomeEntries(biomeCursor, MAP_BATCH_SIZE);
            if (biomes.length != 0) {
                var entries = new ArrayList<String>();
                for (var biome : biomes) {
                    entries.add(biome.biome);
                    if (VoxyNetwork.encodedSize(new VoxyNetwork.BiomeMapBatchS2C(biomeCursor, entries)) > credit) {
                        if (entries.size() > 1) entries.removeLast();
                        break;
                    }
                }
                return new LodServerScheduler.Mappings(new VoxyNetwork.BiomeMapBatchS2C(biomeCursor, entries),
                        blockCursor, biomeCursor + entries.size());
            }
            return null;
        }

        @Override
        public VoxyNetwork.SectionDataS2C load(LodUpdateQueue.Key key) {
            for (var level : server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(key.dimension())) continue;
                var engine = getEngineForLevel(level);
                if (engine == null) return null;
                var section = engine.acquireIfExists(key.section());
                byte[] data = new byte[0];
                if (section != null) {
                    try {
                        var buffer = SaveLoadSystem3.serialize(section).asByteBuffer();
                        data = new byte[buffer.remaining()];
                        buffer.get(data);
                    } finally {
                        section.release();
                    }
                }
                return new VoxyNetwork.SectionDataS2C(key.dimension(), WorldEngine.getLevel(key.section()),
                        WorldEngine.getX(key.section()), WorldEngine.getY(key.section()), WorldEngine.getZ(key.section()), data);
            }
            return null;
        }

        @Override
        public boolean send(UUID id, CustomPacketPayload payload) {
            var player = server.getPlayerList().getPlayer(id);
            return player != null && this.writable(id) && VoxyNetwork.trySendToPlayer(player, payload);
        }
    };

    public void tick() {
        if (!this.isRunning() || this.networkClosed) return;
        for (UUID id : this.voxyClients) {
            var player = this.server.getPlayerList().getPlayer(id);
            if (player == null) {
                this.voxyClients.remove(id);
                this.scheduler.logout(id);
            } else {
                this.scheduler.updateView(view(player));
            }
        }
        this.scheduler.tick(this.transport);
        if (--this.recoveryCooldown <= 0 && (this.recovery == null || !this.recovery.isAlive())) {
            this.recoveryCooldown = 200; // Failed scans retry without a per-tick error loop.
            this.startRecovery();
        }
    }

    private void startRecovery() {
        for (var repair : this.scheduler.updates.repairs().entrySet()) {
            for (var level : this.server.getAllLevels()) {
                if (!level.dimension().location().toString().equals(repair.getKey())) continue;
                var engine = this.getOrCreate(WorldIdentifier.of(level), true);
                if (engine == null) return;
                // Hold the world across capture AND dispatch; shutdown joins before freeing engines.
                this.recovery = new Thread(() -> {
                    try {
                        this.recoverySpoolBytes = LodUpdateRecovery.sweep(this.basePath.resolve("network-recovery"),
                                repair.getKey(), repair.getValue(), this.scheduler.updates,
                                engine::iterateKnownPositions, this.scheduler::interested);
                        this.recoverySweeps++;
                    } catch (InterruptedException | CancellationException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        Logger.error("Voxy LOD recovery failed; repair remains pending", e);
                    } finally {
                        engine.releaseRef();
                    }
                }, "Voxy LOD recovery");
                this.recovery.setDaemon(true);
                this.recovery.start();
                return;
            }
        }
    }

    public LodServerScheduler.Stats networkStats() { return this.scheduler.stats(); }
    public LodUpdateQueue.Stats updateStats() { return this.scheduler.updates.stats(); }
    public long recoverySweeps() { return this.recoverySweeps; }
    public long recoverySpoolBytes() { return this.recoverySpoolBytes; }

    @Override
    public void shutdown() {
        this.networkClosed = true;
        this.scheduler.close();
        this.voxyClients.clear();
        if (this.recovery != null) {
            this.recovery.interrupt();
            boolean interrupted = false;
            while (this.recovery.isAlive()) {
                try { this.recovery.join(1000); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        this.hookedEngines.clear();
        super.shutdown();
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
