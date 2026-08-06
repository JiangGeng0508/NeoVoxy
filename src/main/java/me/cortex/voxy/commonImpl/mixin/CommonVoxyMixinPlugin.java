package me.cortex.voxy.commonImpl.mixin;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class CommonVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean chunkyInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        chunkyInstalled = isModLoaded("chunky");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".chunky.")) {
            return chunkyInstalled;
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

    @Override public List<String> getMixins() { return null; }

    @Override public String getRefMapperConfig() { return null; }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
