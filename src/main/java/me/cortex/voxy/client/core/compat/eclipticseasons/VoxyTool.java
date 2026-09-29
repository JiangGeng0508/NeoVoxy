package me.cortex.voxy.client.core.compat.eclipticseasons;

import com.teamtea.eclipticseasons.client.util.ClientCon;
import java.lang.ref.WeakReference;
import me.cortex.voxy.client.config.VoxyConfig;
import java.lang.reflect.Method;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class VoxyTool {
    private static final int INITIAL_REFRESH_DELAY_TICKS = 200;
    private static final int ENGINE_RETRY_TICKS = 40;
    // Do not keep a disconnected ClientLevel alive merely because no further level tick can clear it.
    private static WeakReference<Level> initialRefreshLevel = new WeakReference<>(null);
    private static int initialRefreshCountdown = INITIAL_REFRESH_DELAY_TICKS;
    private static boolean initialRefreshStarted;
    public static boolean isVoxyTest() {
        return VoxyConfig.CONFIG.eclipticSeasonsSnowLod;
    }

    public static WorldEngine getWorld(Level level) {
        return VoxyTool.getVoxyInstance().getNullable(WorldIdentifier.of((Level)level));
    }

    public static Mapper getMapper(Level level) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : world.getMapper();
    }

    public static WorldSection getWorldSection(WorldEngine into, SectionPos section) {
        int lvl = 0;
        return into.acquireIfExists(lvl, section.x() >> lvl + 1, section.y() >> lvl + 1, section.z() >> lvl + 1);
    }

    public static WorldSection getWorldSection(Level level, SectionPos section) {
        WorldEngine world = VoxyTool.getWorld(level);
        return world == null ? null : VoxyTool.getWorldSection(world, section);
    }

    public static void tryUpdate() {
        if (!VoxyTool.isVoxyTest()) {
            return;
        }
        if (!VoxyConfig.CONFIG.eclipticSeasonsLodAutoReload) {
            return;
        }
        Level level = ClientCon.getUseLevel();
        if (level == null) {
            initialRefreshLevel = new WeakReference<>(null);
            initialRefreshStarted = false;
            initialRefreshCountdown = INITIAL_REFRESH_DELAY_TICKS;
            return;
        }

        if (level != initialRefreshLevel.get()) {
            initialRefreshLevel = new WeakReference<>(level);
            initialRefreshStarted = false;
            // Let the remote Voxy store open and ingest its first batches before walking it. Starting
            // at ClientLevel construction can finish against an empty store and never reach server LOD.
            initialRefreshCountdown = INITIAL_REFRESH_DELAY_TICKS;
        }

        boolean seasonChanged = ClientCon.getAgent().isSnowChange();
        if (!seasonChanged && initialRefreshStarted) {
            return;
        }
        if (!seasonChanged && initialRefreshCountdown-- > 0) {
            return;
        }
        if (SeasonalSnowRefresher.isRunning()) {
            return;
        }

        WorldEngine engine = WorldIdentifier.ofEngineNullable(level);
        if (engine == null || !engine.isLive()) {
            initialRefreshCountdown = ENGINE_RETRY_TICKS;
            return;
        }

        if (seasonChanged) {
            ClientCon.agent.setSnowChange(false);
        }
        initialRefreshStarted = true;
        SeasonalSnowRefresher.start(level, engine);
    }

    @Nullable
    private static VoxyInstance getVoxyInstance() {
        VoxyInstance instance = null;
        try {
            Class<?> clazz = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            Method method = clazz.getDeclaredMethod("getInstance", new Class[0]);
            instance = (VoxyInstance)method.invoke(null, new Object[0]);
        }
        catch (Exception exception) {
            // empty catch block
        }
        return instance;
    }
}

