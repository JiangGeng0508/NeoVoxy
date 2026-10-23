package me.cortex.voxy.client.compat.pingwheel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;

/** Safe during Mixin bootstrap: no optional API or renderer references. */
public final class PingWheelGate {
    public static boolean supported() {
        if (FMLLoader.getDist() != Dist.CLIENT) return false;
        var mods = LoadingModList.get();
        var file = mods == null ? null : mods.getModFileById("pingwheel");
        return file != null && file.getMods().stream().anyMatch(mod -> mod.getModId().equals("pingwheel")
                && mod.getVersion().toString().equals("1.12.2"));
    }
    private PingWheelGate() {}
}
