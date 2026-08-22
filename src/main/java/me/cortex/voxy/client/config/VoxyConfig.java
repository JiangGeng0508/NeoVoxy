package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.neoforged.fml.loading.FMLPaths;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;

    public String ssaoMode;

    public boolean useEnvironmentalFog = true;

    // Runtime toggle for the LOD colour/brightness fix, so it can be compared against raw brightness live.
    // Toggled in-game via "/voxy colorfix" (not exposed in the config screen). true = brightness fix applied
    // (no-shader ~0.955, shader ~1.025), false = raw brightness (1.0, no fix). Read live by MDICSectionRenderer
    // and uploaded to the LOD shaders as uColorFix.
    public boolean colorFix = true;

    // Runtime toggle for the world-curvature fix (seamless start at the vanilla edge + camera-relative, smooth
    // tracking). Toggled in-game via "/voxy curvefix". true = fixed curve, false = original curve (measured from
    // the section origin, so it cuts at the boundary and snaps every 32 blocks). Uploaded to the shader as uCurveFix.
    public boolean curveFix = true;

    // World curvature: simulates standing on a spherical planet
    // 0 = disabled (flat world)
    // 1 = real Earth curvature (6371km radius)
    // Higher values = more extreme curvature (smaller planet effect)
    // Range: 0, or 50-5000 (values 1-49 are invalid and auto-corrected to 50)
    // Inspired by Distant Horizons' earth curvature feature
    public int earthCurveRatio = 0;

    // Distant generation: background-generate chunks beyond the vanilla render distance on the
    // integrated server (singleplayer/LAN host) and ingest them into the LOD store. Chunks are
    // generated only to the LIGHT status (blocks+biomes+light, no entities/ticking) in expanding
    // rings around the player, throttled by server MSPT and the ingest backlog.
    public boolean distantGenEnabled = false;
    // Radius in chunks around the player to generate. 96 chunks = 1536 blocks.
    public int distantGenRadius = 96;
    // How many chunks may generate concurrently. Higher = faster but more server load.
    public int distantGenMaxInFlight = 16;
    // Only start new chunks while the server's average tick time is below this (milliseconds).
    public int distantGenMaxMspt = 45;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (Exception e) {
                    // Nothing may escape here: this runs from the static initialiser, so any throw becomes an
                    // ExceptionInInitializerError, which leaves the class permanently unusable and takes mod
                    // loading down with it. Gson throws unchecked on malformed values (e.g. a fractional number
                    // in an int field), so catch broadly and fall back to defaults.
                    Logger.error("Could not parse config, resetting", e);
                }
                backupUnreadableConfig(path);
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    // Moves a config we could not read aside instead of letting the reset overwrite it, so the user's
    // settings survive for recovery and the bad file is still there to diagnose.
    private static void backupUnreadableConfig(Path path) {
        try {
            Files.move(path, path.resolveSibling(path.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to back up unreadable config", e);
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return FMLPaths.CONFIGDIR
                .get()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
