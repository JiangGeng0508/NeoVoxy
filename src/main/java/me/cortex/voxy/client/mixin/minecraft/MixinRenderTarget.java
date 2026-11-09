package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public class MixinRenderTarget {
    @Inject(method = "destroyBuffers", at = @At("HEAD"))
    private void voxy$releaseView(CallbackInfo ci) {
        var minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.levelRenderer instanceof IGetVoxyRenderSystem access) {
            var renderer = access.voxy$getRenderSystem();
            if (renderer != null) renderer.releaseRenderTarget((RenderTarget) (Object) this);
        }
    }
}
