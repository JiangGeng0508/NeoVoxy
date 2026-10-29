package me.cortex.voxy.client.compat.seedgen;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.world.WorldEngine;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

@EventBusSubscriber(modid = "voxy", value = Dist.CLIENT)
public final class SeedGenSmoke {
    private static int state, ticks;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        String output = System.getProperty("voxy.seedgenSmoke");
        if (output == null) return;
        var mc = Minecraft.getInstance();
        if (++ticks > 5000) throw new AssertionError("SeedGen timeout state=" + state);
        if (state == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            state = 1;
            mc.createWorldOpenFlows().createFreshLevel("fixture",
                    new LevelSettings("SeedGen isolated fixture", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(42, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                            .value().createWorldDimensions(), new TitleScreen());
        }
        if (state == 1 && mc.player != null && IGetVoxyRenderSystem.getNullable() != null) {
            state = 2;
            verifyWriters();
            check(!SeedGenCompat.ownsGeneration(Level.OVERWORLD), "inactive generation must yield");
            Class<?> configType = Class.forName("mobius.voxyseedgen.config.VoxySeedGenConfig");
            Object config = configType.getMethod("get").invoke(null);
            configType.getField("realChunks").setBoolean(config, true);
            check(!SeedGenCompat.ownsGeneration(Level.NETHER), "realChunks setting must not own other dimensions");
            configType.getField("realChunks").setBoolean(config, false);
            float previous = VoxyConfig.CONFIG.sectionRenderDistance;
            VoxyConfig.CONFIG.sectionRenderDistance = 0.625f;
            check((double) method(Class.forName("mobius.voxyseedgen.voxy.LodLevels"), "renderReach")
                    .invoke(null) == 320.0, "fractional render distance ABI");
            VoxyConfig.CONFIG.sectionRenderDistance = previous;
            Class.forName("mobius.voxyseedgen.voxy.RenderReach").getMethod("apply", int.class).invoke(null, 0);
            Class.forName("mobius.voxyseedgen.voxy.CoarseRings").getMethod("follow", int.class).invoke(null, 0);
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
            state = 3;
        }
        if (state == 3 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            Files.writeString(Path.of(output).resolve("complete.pass"),
                    "Real SeedGen client: renderer startup, L1/L4/L5/L15 solid and air writes, persistence, fractional distance and dimension gate passed.\n");
            System.out.println("SEEDGEN_SMOKE PASS");
            state = 4;
            mc.stop();
        }
    }

    private static void verifyWriters() throws Exception {
        Class<?> writerType = Class.forName("mobius.voxyseedgen.voxy.LadderWriter");
        Class<?> statsType = Class.forName("mobius.voxyseedgen.voxy.FillStats");
        Class<?> spansType = Class.forName("mobius.voxyseedgen.terrain.ColumnSpans");
        var constructor = writerType.getDeclaredConstructor(WorldEngine.class, int.class, int.class, statsType,
                boolean.class, Runnable.class);
        constructor.setAccessible(true);
        var statsConstructor = statsType.getDeclaredConstructor();
        statsConstructor.setAccessible(true);
        Method begin = method(writerType, "beginTile", int.class, int.class, long.class, int.class, int.class);
        Method column = method(writerType, "column", int.class, int.class, int.class, spansType);
        Method end = method(writerType, "endPass");
        var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend()));
        AtomicInteger saves = new AtomicInteger();
        AtomicInteger waits = new AtomicInteger();
        engine.setSaveCallback((world, section, nonBlocking, acquired) -> {
            world.storage.saveSection(section);
            section.setNotDirty();
            saves.incrementAndGet();
            return true;
        });
        try {
            for (int level : new int[]{1, 4, 5, 15}) {
                int stride = 1 << level, height = 32 * stride;
                Object writer = constructor.newInstance(engine, 0, height, statsConstructor.newInstance(),
                        false, (Runnable) waits::incrementAndGet);
                for (boolean solid : new boolean[]{true, false}) {
                    int sx = solid ? 1 : 2;
                    Object spans = spansType.getConstructor(int.class, int.class, int.class).newInstance(0, 15, height);
                    if (solid) spansType.getMethod("add", int.class, int.class,
                            net.minecraft.world.level.block.state.BlockState.class, net.minecraft.core.Holder.class)
                            .invoke(spans, 0, height, Blocks.STONE.defaultBlockState(),
                                    Minecraft.getInstance().level.getBiome(Minecraft.getInstance().player.blockPosition()));
                    begin.invoke(writer, 0, 0, 0L, level, level);
                    for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++)
                        column.invoke(writer, (sx * 32 + x) * stride, z * stride, stride, spans);
                    int before = saves.get();
                    end.invoke(writer);
                    check(saves.get() > before, "stack not saved: level=" + level + " solid=" + solid);
                    // Bypass the active-section cache: coverage and voxels must survive storage.
                    WorldSection loaded = WorldSection._createRawUntrackedUnsafeSection(level, sx, 0, 0);
                    check(engine.storage.loadSection(loaded) == 0, "stack not persisted");
                    check(loaded.getKnownOctants() == 255, "coverage not persisted at L" + level);
                    check(Mapper.isAir(loaded.get(0)) != solid, "voxel not persisted");
                    loaded.acquire();
                    loaded.release();
                }
            }
            check(waits.get() == saves.get(), "air path bypassed SeedGen backpressure");
        } finally { engine.free(); }
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
