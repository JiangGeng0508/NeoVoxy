package me.cortex.voxy.client.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.compat.create.DistantTrainConfig;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real menu binding, JSON file lifecycle and NeoForge config bridge without a game window. */
class RenderDistanceConfigTest {
    @TempDir Path directory;
    private final Map<Field, Object> savedConfig = new LinkedHashMap<>();
    private final Map<Field, Object> savedStatics = new LinkedHashMap<>();
    private VoxyConfig config;
    private Path previousConfigDirectory;
    private IConfigSpec.ILoadedConfig previousLoadedConfig;

    @BeforeEach
    void isolateConfigFilesAndRuntimeState() throws Exception {
        config = VoxyConfig.CONFIG;
        for (Field field : VoxyConfig.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) savedConfig.put(field, field.get(config));
        }
        saveStatic(VoxyCommon.class, "FACTORY");
        saveStatic(SableContraptionRenderDistance.class, "runtimeClientConfig");
        saveStatic(DistantTrainConfig.class, "clientEnabled");
        saveStatic(DistantTrainConfig.class, "clientMaxDistance");
        saveStatic(DistantTrainConfig.class, "clientContraptionsEnabled");
        saveStatic(DistantTrainConfig.class, "clientContraptionMaxDistance");
        saveStatic(RenderStatistics.class, "enabled");
        previousConfigDirectory = FMLPaths.CONFIGDIR.get();
        field(FMLPaths.class, "absolutePath").set(FMLPaths.CONFIGDIR, directory);
        field(VoxyCommon.class, "FACTORY").set(null, (VoxyCommon.IInstanceFactory) () -> {
            throw new AssertionError("Config persistence must not create a world engine");
        });
        previousLoadedConfig = (IConfigSpec.ILoadedConfig) field(
                VoxyNeoForgeConfig.SPEC.getClass(), "loadedConfig").get(VoxyNeoForgeConfig.SPEC);
    }

    @AfterEach
    void restoreRuntimeState() throws Exception {
        VoxyNeoForgeConfig.SPEC.acceptConfig(previousLoadedConfig);
        for (var entry : savedConfig.entrySet()) entry.getKey().set(config, entry.getValue());
        for (var entry : savedStatics.entrySet()) entry.getKey().set(null, entry.getValue());
        field(FMLPaths.class, "absolutePath").set(FMLPaths.CONFIGDIR, previousConfigDirectory);
        WorldSection.setArrayPoolCapMiB(config.sectionArrayPoolMiB);
    }

    @Test
    void everyTwoChunkMenuStepSurvivesJsonAndTomlRoundTrips() throws Exception {
        var option = distanceOption(config);
        var range = option.rangeProvider.apply(null);
        assertEquals(10, range.min());
        assertEquals(1024, range.max());
        assertEquals(1, range.step());
        CommentedConfig toml = attachToml("");

        // All 1,015 GUI positions, including 20/22/62/64/66 chunks and both ends.
        for (int step = range.min(); step <= range.max(); step += range.step()) {
            int chunks = step * 2;
            float distance = chunks / 32f;
            option.setter.accept(step);
            assertEquals(Integer.toString(chunks), option.formatter.format(step).getString());
            config.save();
            assertEquals(distance, loadJson().sectionRenderDistance, "JSON changed " + chunks + " chunks");

            invoke(VoxyNeoForgeConfig.class, "syncFromVoxyConfig");
            assertEquals(distance, toml.<Number>get("sectionRenderDistance").floatValue());
            toml = attachToml(new TomlWriter().writeToString(toml));
            config.sectionRenderDistance = 16; // Force the reverse bridge to restore the saved value.
            invoke(VoxyNeoForgeConfig.class, "syncToVoxyConfig");
            assertEquals(distance, VoxyNeoForgeConfig.getSectionRenderDistance());
            assertEquals(distance, config.sectionRenderDistance, "TOML changed " + chunks + " chunks");
            assertEquals(step, option.getter.get());
            VoxyConfig loaded = loadJson();
            assertEquals(distance, loaded.sectionRenderDistance);
            assertEquals(chunks * 16.0, loaded.createLodRadius());
            assertEquals(chunks * 16, loaded.getFarEntityRenderDistanceBlocks());
        }
        assertFalse(Files.exists(directory.resolve("voxy-config.json.tmp")));
    }

    @Test
    void fractionalTomlFilesReloadWithoutTruncation() throws Exception {
        Path path = directory.resolve("voxy-client.toml");
        // Include a valid non-GUI fraction: the file format does not quantize to slider steps.
        for (double distance : new double[]{0.625, 0.6875, 1.0625, 16.0625, 16.03, 64}) {
            CommentedConfig toml = attachToml("sectionRenderDistance = " + distance);
            Files.writeString(path, new TomlWriter().writeToString(toml));
            attachToml(Files.readString(path));
            invoke(VoxyNeoForgeConfig.class, "syncToVoxyConfig");
            assertEquals((float) distance, config.sectionRenderDistance);
            assertEquals((float) distance, loadJson().sectionRenderDistance);
            invoke(VoxyNeoForgeConfig.class, "syncFromVoxyConfig");
            assertEquals((float) distance, VoxyNeoForgeConfig.getSectionRenderDistance());
        }
    }

    @Test
    void defaultsAndLegacyIntegerFilesRemainCompatible() throws Exception {
        writeJson("{}");
        assertEquals(16f, loadJson().sectionRenderDistance);
        attachToml("");
        assertEquals(16f, VoxyNeoForgeConfig.getSectionRenderDistance());
        for (int distance : new int[]{2, 16, 64}) {
            writeJson("{\"section_render_distance\":" + distance + "}");
            assertEquals((float) distance, loadJson().sectionRenderDistance);
            attachToml("sectionRenderDistance = " + distance);
            invoke(VoxyNeoForgeConfig.class, "syncToVoxyConfig");
            assertEquals((float) distance, config.sectionRenderDistance);
            assertEquals((float) distance, loadJson().sectionRenderDistance);
        }
    }

    @Test
    void jsonAndTomlCorrectOutOfRangeDistancesToTheSameLimits() throws Exception {
        for (float[] sample : new float[][]{{-10, 0.625f}, {0, 0.625f}, {0.624f, 0.625f}, {64.01f, 64}, {100, 64}}) {
            writeJson("{\"section_render_distance\":" + sample[0] + "}");
            VoxyConfig loaded = loadJson();
            assertEquals(sample[1], loaded.sectionRenderDistance);
            loaded.save();
            assertEquals(sample[1], loadJson().sectionRenderDistance);
            attachToml("sectionRenderDistance = " + sample[0]);
            invoke(VoxyNeoForgeConfig.class, "syncToVoxyConfig");
            assertEquals(sample[1], config.sectionRenderDistance);
            assertEquals(sample[1], VoxyNeoForgeConfig.getSectionRenderDistance());
        }
    }

    private CommentedConfig attachToml(String text) throws Exception {
        CommentedConfig data = new TomlParser().parse(text);
        VoxyNeoForgeConfig.SPEC.correct(data);
        assertTrue(VoxyNeoForgeConfig.SPEC.isCorrect(data));
        // Bind real NeoForge values to parsed TOML without dispatching Minecraft mod events.
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        constructor.setAccessible(true);
        VoxyNeoForgeConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(data, null, null));
        return data;
    }

    private static SodiumConfigBuilder.IntOption distanceOption(VoxyConfig config) throws Exception {
        var method = VoxyConfigMenu.class.getDeclaredMethod("renderingPage", VoxyConfig.class, String.class);
        method.setAccessible(true);
        var page = (SodiumConfigBuilder.Page) method.invoke(null, config, "voxy:reload_test");
        for (var group : page.groups) {
            for (var option : group.options) {
                if (option.id.equals("voxy:render_distance")) return (SodiumConfigBuilder.IntOption) option;
            }
        }
        throw new AssertionError("Missing render distance menu option");
    }

    private void writeJson(String json) throws Exception {
        Files.writeString(directory.resolve("voxy-config.json"), json);
    }

    private static VoxyConfig loadJson() throws Exception {
        return (VoxyConfig) invoke(VoxyConfig.class, "loadOrCreate");
    }

    private static Object invoke(Class<?> type, String name) throws Exception {
        var method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void saveStatic(Class<?> type, String name) throws Exception {
        Field field = field(type, name);
        savedStatics.put(field, field.get(null));
    }
}
