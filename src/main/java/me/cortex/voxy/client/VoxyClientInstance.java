package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.compat.ReForgedPlayCompat;
import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.StorageConfigUtil;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.config.section.SectionStorageConfig;
import me.cortex.voxy.commonImpl.ImportManager;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.common.world.WorldSection;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

public class VoxyClientInstance extends VoxyInstance {
    private static final Config DEFAULT_STORAGE_CONFIG = createDefaultStorageConfig();

    private final Config config;
    private final Path basePath;
    /** True when replay playback supplies its own LOD storage, so ingest must stay off. */
    private final boolean noIngestOverride;

    public VoxyClientInstance() {
        super();
        var path = ReForgedPlayCompat.getReplayStoragePath();
        this.noIngestOverride = path != null;
        if (path == null) {
            path = getBasePath();
        }
        this.basePath = path.normalize();
        this.config = StorageConfigUtil.getCreateStorageConfig(
                Config.class,
                c -> c.version == 1 && c.sectionStorageConfig != null,
                () -> DEFAULT_STORAGE_CONFIG,
                this.basePath);
        this.updateDedicatedThreads();
    }

    @Override
    public void updateDedicatedThreads() {
        int target = VoxyConfig.CONFIG.serviceThreads;
        if (!VoxyConfig.CONFIG.dontUseSodiumBuilderThreads) {
            int sodiumThreads = getSodiumBuilderThreadCount();
            if (sodiumThreads >= 0) {
                this.setNumThreads(Math.max(1, target - sodiumThreads));
                return;
            }
        }
        this.setNumThreads(target);
    }

    private static int getSodiumBuilderThreadCount() {
        var renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return -1;
        }
        var renderSectionManager = ((AccessorSodiumWorldRenderer) renderer).getRenderSectionManager();
        return renderSectionManager == null ? -1 : renderSectionManager.getBuilder().getTotalThreadCount();
    }

    @Override
    protected ImportManager createImportManager() {
        return new ClientImportManager();
    }

    @Override
    protected SectionStorage createStorage(WorldIdentifier identifier) {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty(ConfigBuildCtx.BASE_SAVE_PATH, this.basePath.toString());
        ctx.setProperty(ConfigBuildCtx.WORLD_IDENTIFIER, identifier.getWorldId());
        ctx.setProperty(ConfigBuildCtx.PLAYER_UUID,
                Minecraft.getInstance().getUser().getProfileId().toString().replace(':', '-'));
        ctx.pushPath(ConfigBuildCtx.DEFAULT_STORAGE_PATH);
        return this.config.sectionStorageConfig.build(ctx);
    }

    public Path getStorageBasePath() {
        return this.basePath;
    }

    @Override
    public boolean isIngestEnabled(WorldIdentifier worldId) {
        //When a voxy server streams LOD data, local ingest is suspended to avoid clobbering
        //authoritative data and wasting CPU on duplicate voxelization.
        return (!this.noIngestOverride) && !VoxyClientNetwork.isServerAuthoritative() && VoxyConfig.CONFIG.ingestEnabled;
    }

    @Override
    public void shutdown() {
        super.shutdown();
        // 实例销毁后再释放共享 GPU 缓存，避免仍在使用的渲染器拿到失效资源。
        RenderResourceReuse.clearResources();
        WorldSection.trimArrayPool(WorldSection.DEFAULT_ARRAY_POOL_ARRAYS);
    }

    private static class Config {
        public int version = 1;
        public boolean disabled = false;
        public SectionStorageConfig sectionStorageConfig;
    }

    private static Config createDefaultStorageConfig() {
        var config = new Config();
        config.sectionStorageConfig = StorageConfigUtil.createDefaultSerializer();
        return config;
    }

    private static Path getBasePath() {
        var minecraft = Minecraft.getInstance();
        var fallback = minecraft.gameDirectory.toPath().resolve(".voxy").resolve("saves");
        var integratedServer = minecraft.getSingleplayerServer();
        if (integratedServer != null) {
            return integratedServer.getWorldPath(LevelResource.ROOT).resolve("voxy").toAbsolutePath();
        }

        var gameMode = minecraft.gameMode;
        if (gameMode == null) {
            Logger.error("Network handle null");
            return fallback.resolve("UNKNOWN").toAbsolutePath();
        }

        var serverInfo = gameMode.connection.getServerData();
        if (serverInfo == null) {
            Logger.error("Server info null");
            return fallback.resolve("UNKNOWN").toAbsolutePath();
        }
        return (serverInfo.isRealm() ? fallback.resolve("realms") : fallback.resolve(serverInfo.ip.replace(":", "_")))
                .toAbsolutePath();
    }
}
