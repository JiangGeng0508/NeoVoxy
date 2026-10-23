package me.cortex.voxy.client.compat.xaero;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;

/** No references to Xaero classes: also used before Mixin target resolution. */
public final class XaeroGate {
    public static boolean supported() {
        if (FMLLoader.getDist() != Dist.CLIENT) return false;
        var mods = LoadingModList.get();
        var file = mods == null ? null : mods.getModFileById("xaeroworldmap");
        if (file == null) return false;
        return file.getMods().stream().anyMatch(mod -> mod.getModId().equals("xaeroworldmap")
                && mod.getVersion().toString().equals("1.47.0"));
    }
    private XaeroGate() {}
}
