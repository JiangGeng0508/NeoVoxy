package me.cortex.voxy.client.compat.pingwheel;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import nx.pingwheel.common.config.ClientConfig;
import nx.pingwheel.common.core.GameContext;
import nx.pingwheel.common.core.PingController;
import nx.pingwheel.common.core.PingManager;
import nx.pingwheel.common.core.PingData;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PingWheelSmoke {
    private static int state, ticks, wait;
    private static final java.lang.reflect.Method PERFORM;
    private static final java.lang.reflect.Field POSITION;
    static {
        try {
            PERFORM = PingController.class.getDeclaredMethod("performPingAction", float.class);
            PERFORM.setAccessible(true);
            POSITION = PingData.class.getDeclaredField("pos");
            POSITION.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static void tick(Path output) throws Exception {
        var mc = Minecraft.getInstance();
        if (++ticks > 5000) throw new AssertionError("PingWheel smoke timeout state=" + state);
        if (ticks % 200 == 0) System.out.println("PINGWHEEL_SMOKE state=" + state + " screen=" + mc.screen);
        if (state == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            if (!PingWheelGate.supported()) throw new AssertionError("supported gate");
            state = 1;
            mc.createWorldOpenFlows().createFreshLevel("fixture",
                    new LevelSettings("PingWheel isolated fixture", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new TitleScreen());
        }
        if (state == 1 && mc.player != null && mc.level != null && IGetVoxyRenderSystem.getNullable() != null) {
            mc.setScreen(null);
            mc.player.connection.sendCommand("tp @s 0.5 200 0.5 -90 0");
            state = 2;
            wait = 60;
        }
        if (state >= 2 && state <= 8 && mc.player != null) {
            mc.setScreen(null);
            mc.player.setYRot(-90);
            mc.player.setXRot(0);
            mc.player.getAbilities().flying = true;
            if (--wait > 0) return;
            var renderer = IGetVoxyRenderSystem.getNullable();
            if (renderer == null) return;
            if (state == 2) {
                var engine = renderer.getEngine();
                int sy = ((int) Math.floor(mc.player.getEyePosition().y)) >> 9;
                // Coarse known corridor; x=8192 exceeds both ordinary PingWheel distance settings.
                for (int sx = 0; sx <= 16; sx++) {
                    var section = engine.acquire(4, sx, sy, 0);
                    try {
                        synchronized (section) {
                            section.setUniform(sx == 16 ? Mapper.composeMappingId((byte) 15,
                                    engine.getMapper().getIdForBlockState(Blocks.STONE.defaultBlockState()), 0) : Mapper.AIR);
                            section.setKnownOctants(255);
                        }
                        engine.markDirty(section);
                        engine.storage.saveSection(section);
                    } finally { section.release(); }
                }
                ClientConfig.HANDLER.getConfig().setChannel("voxy-fixture");
                if (ClientConfig.HANDLER.getConfig().getRaycastDistance() != 1024
                        || ClientConfig.HANDLER.getConfig().getPingDistance() != 2048)
                    throw new AssertionError("probe must exercise unchanged PingWheel distance defaults");
                PingManager.clearPings();
                System.out.println("PINGWHEEL_SMOKE eye=" + mc.player.getEyePosition() + " pipeline=" + renderer.getClass().getName());
                PERFORM.invoke(null, 1.0f);
                state = 3; wait = 100;
            } else if (state == 3) {
                verifyPosition(8192, "LOD pick beyond ordinary ray distance");
                Files.writeString(output.resolve("lod.pass"), "Actual MISS bridge sent one terrain ping at x=8192 with PingWheel defaults 1024/2048 through integrated networking.\n");
                PingManager.clearPings();
                VoxyConfig.CONFIG.pingwheelLodPicking = false;
                PERFORM.invoke(null, 1.0f);
                state = 4; wait = 40;
            } else if (state == 4) {
                if (!PingManager.PING_REPO.isEmpty()) throw new AssertionError("disabled integration sent ping");
                Files.writeString(output.resolve("disabled.pass"), "Disabled integration keeps an ordinary sky MISS silent.\n");
                VoxyConfig.CONFIG.pingwheelLodPicking = true;
                VoxyConfig.CONFIG.earthCurveRatio = 50;
                PERFORM.invoke(null, 1.0f);
                state = 5; wait = 40;
            } else if (state == 5) {
                if (!PingManager.PING_REPO.isEmpty()) throw new AssertionError("curvature integration sent ping");
                VoxyConfig.CONFIG.earthCurveRatio = 0;
                Files.writeString(output.resolve("curve.pass"), "Curvature disables additional LOD picking.\n");
                PERFORM.invoke(null, 1.0f);
                PingWheelCompat.stop(); // Invalidate before any client-thread completion can run.
                state = 6; wait = 60;
            } else if (state == 6) {
                if (!PingManager.PING_REPO.isEmpty()) throw new AssertionError("cancelled operation sent ping");
                Files.writeString(output.resolve("cancel.pass"), "Invalidated in-flight completion sends no ping.\n");
                int eyeY = (int) Math.floor(mc.player.getEyePosition().y);
                mc.player.connection.sendCommand("setblock 4 " + eyeY + " 0 minecraft:gold_block");
                state = 7; wait = 40;
            } else if (state == 7) {
                PERFORM.invoke(null, 1.0f);
                state = 8; wait = 60;
            } else if (state == 8) {
                verifyPosition(4, "original near block");
                Files.writeString(output.resolve("near.pass"), "Original vanilla hit wins over distant LOD; exactly one ping received.\n");
                state = 9; // disconnect pumps client ticks; prevent re-entrant probe actions.
                mc.level.disconnect(); // Normal quit first signals the integrated server to stop.
                mc.disconnect(new TitleScreen());
                state = 10;
            }
        }
        if (state == 10 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            Files.writeString(output.resolve("complete.pass"), "PingWheel smoke completed and world closed normally.\n");
            System.out.println("PINGWHEEL_SMOKE PASS");
            mc.stop();
        }
    }
    private static void verifyPosition(double x, String label) throws Exception {
        if (PingManager.PING_REPO.size() != 1) throw new AssertionError(label + ": expected one ping, got " + PingManager.PING_REPO.size());
        var ping = PingManager.PING_REPO.getFirst();
        Vec3 position = (Vec3) POSITION.get(ping);
        if (Math.abs(position.x - x) > 0.01 || ping.entityId != null || ping.dimension != GameContext.getDimension())
            throw new AssertionError(label + ": " + position);
    }
}
