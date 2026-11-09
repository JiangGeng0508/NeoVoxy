package me.cortex.voxy.client.mixin.iris;

import me.cortex.voxy.client.iris.ShaderLoadError;
import me.cortex.voxy.common.Logger;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = Iris.class, remap = false)
public class MixinIris {
    @org.spongepowered.asm.mixin.injection.Inject(method = "destroyEverything", at = @At("HEAD"))
    private static void voxy$releaseSecondaryPasses(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        var level = net.minecraft.client.Minecraft.getInstance().levelRenderer;
        if (level instanceof me.cortex.voxy.client.core.IGetVoxyRenderSystem access) {
            var renderer = access.voxy$getRenderSystem();
            if (renderer != null) renderer.releaseSecondaryPasses();
        }
    }

    @Redirect(method = "createPipeline", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;getProgramSet(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;"))
    private static ProgramSet voxy$redirectProgramSet(ShaderPack shaderPack, NamespacedId dim) {
        try {
            String realName = me.cortex.voxy.client.compat.vista.VistaCompat.shaderDimension(dim.getName());
            return shaderPack.getProgramSet(realName.equals(dim.getName()) ? dim
                    : new NamespacedId(dim.getNamespace(), realName));
        } catch (ShaderLoadError e) {
            Logger.error(e);
            return null;
        }
    }
}
