package me.cortex.voxy.client.distgen;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives {@link DistantWorldGenerator}s from the integrated server's tick loop.
 *
 * Runs automatically in singleplayer/LAN-host when Voxy + ingest + distant generation
 * are enabled. Dedicated servers never have a Voxy instance on this dist, so the
 * tick handler is inert there.
 *
 * All state is owned by the server thread (ServerTickEvent fires there); the pause
 * flag is volatile because the client command thread toggles it.
 */
@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class DistantGenerationManager {
    //Dont start new chunks while voxy's ingest queue is this deep - it means voxelization
    // cant keep up and queued sections would just pin memory
    private static final int MAX_INGEST_BACKLOG = 2000;
    private static final int BITMAP_SAVE_INTERVAL_TICKS = 20*60;//60 seconds

    //ConcurrentHashMap only because the client command thread reads it for /voxy distantgen status;
    // all mutation happens on the server thread
    private static final Map<ServerLevel, DistantWorldGenerator> GENERATORS = new ConcurrentHashMap<>();
    private static volatile boolean paused = false;
    private static int tickCounter = 0;

    //When the standalone Groundwork pregenerator is installed it handles distant generation and
    // feeds voxy through its bridge - running both would generate everything twice
    private static Boolean groundworkPresent;

    private static boolean isGroundworkPresent() {
        if (groundworkPresent == null) {
            groundworkPresent = net.neoforged.fml.ModList.get().isLoaded("groundwork");
        }
        return groundworkPresent;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (isGroundworkPresent()) {
            return;
        }
        var cfg = VoxyConfig.CONFIG;
        boolean active = cfg.enabled && cfg.ingestEnabled && cfg.distantGenEnabled && !paused
                && VoxyCommon.getInstance() instanceof VoxyClientInstance;
        if (!active) {
            if (!GENERATORS.isEmpty()) {
                shutdownAll();
            }
            return;
        }
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        var server = event.getServer();

        //Backpressure: never enqueue faster than the rest of the pipeline can drain
        boolean allowNewWork = instance.getIngestService().getTaskCount() < MAX_INGEST_BACKLOG
                && instance.savingServiceRateLimiter.getAsBoolean()
                && server.getAverageTickTimeNanos() < cfg.distantGenMaxMspt * 1_000_000L;

        for (ServerLevel level : server.getAllLevels()) {
            var players = level.players();
            var generator = GENERATORS.get(level);
            if (generator == null) {
                if (players.isEmpty()) {
                    continue;//Dont create generators for empty dimensions
                }
                generator = new DistantWorldGenerator(level, instance.getStorageBasePath().resolve("distantgen"));
                GENERATORS.put(level, generator);
            }
            generator.harvest();
            ServerPlayer player = players.isEmpty() ? null : players.get(0);
            if (allowNewWork && player != null) {
                generator.topUp(player, cfg.distantGenMaxInFlight, cfg.distantGenRadius);
            }
        }

        if (++tickCounter >= BITMAP_SAVE_INTERVAL_TICKS) {
            tickCounter = 0;
            for (var generator : GENERATORS.values()) {
                generator.saveIfDirty();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        shutdownAll();
    }

    private static void shutdownAll() {
        for (var generator : GENERATORS.values()) {
            generator.shutdown();
        }
        GENERATORS.clear();
    }

    public static void setPaused(boolean pause) {
        paused = pause;
    }

    public static boolean isPaused() {
        return paused;
    }

    /** Snapshot of per-dimension status lines. Only for display; read from any thread. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        for (var generator : GENERATORS.values()) {
            lines.add(generator.getLevel().dimension().location() + ": "
                    + generator.describeStatus(VoxyConfig.CONFIG.distantGenRadius));
        }
        return lines;
    }

    /** Reset progress for the given level (must be scheduled onto the server thread). */
    public static boolean resetLevel(ServerLevel level) {
        var generator = GENERATORS.remove(level);
        if (generator == null) {
            return false;
        }
        generator.reset();
        return true;
    }
}
