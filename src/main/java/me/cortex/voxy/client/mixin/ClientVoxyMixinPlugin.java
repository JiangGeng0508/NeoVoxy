package me.cortex.voxy.client.mixin;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean connectorInstalled = false;

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = isModLoaded("valkyrienskies");
        nvidiumInstalled = isModLoaded("nvidium");
        connectorInstalled = isModLoaded("connector");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".iris.") || mixinClassName.contains(".flashback.") || mixinClassName.contains(".nvidium.")) {
            return false;
        }
        if (mixinClassName.contains(".reforgedplay.")) {
            return isModLoaded("reforgedplaymod") || isModLoaded("replaymod");
        }
        if (mixinClassName.contains(".sodium.")) {
            return isModLoaded("sodium");
        }
        return true;
    }

    private static boolean isModLoaded(String modId) {
        var runtimeMods = ModList.get();
        if (runtimeMods != null) {
            return runtimeMods.isLoaded(modId);
        }

        var loadingMods = LoadingModList.get();
        return loadingMods != null && loadingMods.getModFileById(modId) != null;
    }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
