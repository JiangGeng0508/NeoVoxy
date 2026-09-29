package me.cortex.voxy.client.core.compat.eclipticseasons;

import me.cortex.voxy.common.Logger;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public final class EsCompatGate {
    private static final ArtifactVersion MIN_VERSION = new DefaultArtifactVersion("0.15.0-rc");
    private static Boolean cached;

    private EsCompatGate() {}

    public static boolean shouldArm() {
        Boolean v = cached;
        if (v == null) cached = v = compute();
        return v;
    }

    private static boolean compute() {
        //LoadingModList, not ModList: the earliest caller is the mixin plugin, before ModList exists
        var list = LoadingModList.get();
        var file = list == null ? null : list.getModFileById("eclipticseasons");
        if (file == null) return false;
        var mods = file.getMods();
        var version = mods.isEmpty() ? null : mods.get(0).getVersion();
        if (version != null && version.compareTo(MIN_VERSION) >= 0) return true;
        Logger.warn("EclipticSeasons " + version + " is older than " + MIN_VERSION
                + " and lacks symbols the seasonal LOD bridge links against; seasonal LOD stays disabled."
                + " Update EclipticSeasons to see snow/ice/seasonal models at LOD range.");
        return false;
    }
}
