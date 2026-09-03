package me.cortex.voxy.commonImpl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated-server driver: owns the {@link VoxyServerInstance} lifecycle, feeds chunk data
 * into the ingest pipeline and drains the network broadcast queue each tick.
 *
 * <p>Every handler is inert unless the active instance is a {@link VoxyServerInstance},
 * so integrated servers (which run a client instance) are unaffected.
 */
@EventBusSubscriber(modid = VoxyCommon.MOD_ID)
public final class VoxyServer {
    private VoxyServer() {}

    /** Chunk columns already re-ingested this tick (block-edit debounce). */
    private static final Set<Long> DIRTY_COLUMNS = ConcurrentHashMap.newKeySet();

    private static VoxyServerInstance instance() {
        var instance = VoxyCommon.getInstance();
        return instance instanceof VoxyServerInstance srv ? srv : null;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        if (!VoxyCommon.IS_DEDICATED_SERVER) {
            return;
        }
        if (VoxyCommon.getInstance() != null) {
            return;
        }
        try {
            var server = event.getServer();
            VoxyCommon.setInstanceFactory(() -> new VoxyServerInstance(server));
            VoxyCommon.createInstance();
            Logger.info("Voxy server support enabled");
        } catch (Exception e) {
            Logger.error("Voxy failed starting server instance", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (instance() == null) {
            return;
        }
        DIRTY_COLUMNS.clear();
        try {
            VoxyCommon.shutdownInstance();
        } catch (Exception e) {
            Logger.error("Voxy failed shutting down server instance", e);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        var instance = instance();
        if (instance == null) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            instance.onPlayerLogout(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        var instance = instance();
        if (instance == null) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            try {
                instance.resyncPlayer(player);
            } catch (Exception e) {
                Logger.error("Voxy failed resyncing changed-dimension player", e);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (instance() == null) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel && event.getChunk() instanceof LevelChunk chunk) {
            try {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            } catch (Exception e) {
                Logger.error("Voxy failed ingesting server chunk", e);
            }
        }
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (instance() == null) {
            return;
        }
        try {
            var chunk = event.getLevel().getChunkSource().getChunkNow(event.getPos().x, event.getPos().z);
            if (chunk instanceof LevelChunk levelChunk) {
                VoxelIngestService.tryAutoIngestChunk(levelChunk);
            }
        } catch (Exception e) {
            Logger.error("Voxy failed ingesting watched server chunk", e);
        }
    }

    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        var instance = instance();
        if (instance == null || !instance.isEnabled()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var pos = event.getPos();
        long column = (((long) pos.getX() >> 4) << 32) | ((pos.getZ() >> 4) & 0xFFFFFFFFL);
        if (!DIRTY_COLUMNS.add(column)) {
            return;
        }
        try {
            var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk instanceof LevelChunk levelChunk) {
                VoxelIngestService.tryAutoIngestChunk(levelChunk);
            }
        } catch (Exception e) {
            Logger.error("Voxy failed re-ingesting edited server chunk", e);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        var instance = instance();
        if (instance == null) {
            return;
        }
        DIRTY_COLUMNS.clear();
        try {
            instance.tick();
        } catch (Exception e) {
            Logger.error("Voxy failed ticking server instance", e);
        }
    }
}
