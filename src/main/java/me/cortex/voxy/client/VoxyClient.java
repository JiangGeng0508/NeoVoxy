package me.cortex.voxy.client;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.compat.eclipticseasons.EsCompatGate;
import me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalLod;
import me.cortex.voxy.client.core.compat.eclipticseasons.SeasonalMeshView;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.Minecraft;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.NonWritableChannelException;
import java.util.HashSet;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public class VoxyClient {
    private static final HashSet<String> FREX = new HashSet<>();
    private static FileLock EXCLUSIVE_LOCK;
    //RenderSystem.initRenderer can run again after a GPU/renderer reset; re-initialising would
    //re-acquire the lock file, rebuild the shared index buffer and re-register the instance.
    private static boolean INITIALIZED;

    public static synchronized void initVoxyClient() {
        if (INITIALIZED) {
            return;
        }
        INITIALIZED = true;

        Capabilities.init();
        if (!isSystemSupported()) {
            Logger.error("Voxy is unsupported on your system.");
            return;
        }

        if (!acquireExclusiveLockIfRequested()) {
            return;
        }

        SharedIndexBuffer.INSTANCE.id();
        VoxyCommon.setInstanceFactory(VoxyClientInstance::new);
        VoxyClientNetwork.init();
        registerSeasonalLod();

        if (!Capabilities.INSTANCE.subgroup) {
            Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
        }
    }

    private static boolean isSystemSupported() {
        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }
        return Capabilities.INSTANCE.compute
                && Capabilities.INSTANCE.indirectParameters
                && !Capabilities.INSTANCE.hasBrokenDepthSampler;
    }

    /** 独占锁是可选的；获取失败时保持原有的整体禁用行为。 */
    private static boolean acquireExclusiveLockIfRequested() {
        if (!Boolean.parseBoolean(System.getProperty("voxy.exclusiveLock", "false"))) {
            return true;
        }

        var lockDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve(".voxy");
        if (!lockDirectory.toFile().isDirectory()) {
            lockDirectory.toFile().mkdir();
        }
        try {
            FileOutputStream stream = new FileOutputStream(lockDirectory.resolve("voxy.lock").toFile());
            EXCLUSIVE_LOCK = stream.getChannel().lock(0, Long.MAX_VALUE, false);
            return true;
        } catch (NonWritableChannelException | IOException e) {
            Logger.error("Failed to acquire exclusive voxy lock file, mod will be disabled");
            return false;
        }
    }

    private static void registerSeasonalLod() {
        if (EsCompatGate.shouldArm()) {
            SeasonalLod.view = new SeasonalMeshView();
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (VoxyCommon.isAvailable()) {
            event.getDispatcher().register(VoxyCommands.register());
        }
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
